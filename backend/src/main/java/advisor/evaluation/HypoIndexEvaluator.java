package advisor.evaluation;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import advisor.candidates.CandidateIndex;
import advisor.database.PostgresManager;
import advisor.database.PostgresManager.ConnectionSession;
import advisor.planning.QueryPlan;

/**
 * Fast candidate screening via PostgreSQL's HypoPG extension: a
 * hypothetical index exists only in the planner's cost model — never real
 * storage, never a table lock, created/dropped in microseconds instead of
 * a real CREATE/DROP INDEX cycle. This is the mechanism Stretch Goal #1
 * called for: screen cheaply at scale via HypoPG, reserve real
 * CREATE INDEX/EXPLAIN ANALYZE (IndexEvaluator) for validating finalists.
 *
 * Trade-off, and why this doesn't replace IndexEvaluator: a hypothetical
 * index is invisible to real query execution — EXPLAIN ANALYZE against it
 * would just run the query for real and ignore it, since it doesn't
 * physically exist. So this can only compare the planner's ESTIMATED cost
 * before/after, never real measured execution time. IndexEvaluator remains
 * the source of truth for the numbers actually shown in a Recommendation;
 * this class exists as a demonstrated, working, additional capability
 * (see docs) rather than a full pipeline replacement in this pass.
 *
 * Critical detail: hypothetical indexes are session-local (per physical
 * connection) HypoPG state — invisible from any other connection,
 * including a different one borrowed from the same HikariCP pool. Every
 * statement in evaluate() below therefore runs on one pinned connection
 * via PostgresManager.openSession(), not the pool-per-call pattern the
 * rest of this codebase uses for ordinary queries/DDL (which are real,
 * persisted database state, visible from any connection).
 */
public class HypoIndexEvaluator {

    private final PostgresManager db;
    private final Set<String> validTables;
    private final Set<String> validColumns;

    public HypoIndexEvaluator(PostgresManager db) {
        this.db = db;
        assertSafeDatabase();
        assertHypoPgAvailable();
        this.validTables = loadValidTables();
        this.validColumns = loadValidColumns();
    }

    private void assertSafeDatabase() {
        String currentDb = db.executeScalar("SELECT current_database();");

        if (!"index_advisor".equals(currentDb)) {
            throw new IllegalStateException(
                "Refusing to run index experiments against database '" + currentDb + "'. "
                    + "HypoIndexEvaluator only runs on the local 'index_advisor' sandbox, "
                    + "never the shared Supabase 'ecom' database."
            );
        }
    }

    private void assertHypoPgAvailable() {
        String installedVersion = db.executeScalar(
            "SELECT installed_version FROM pg_available_extensions WHERE name = 'hypopg';"
        );

        if (installedVersion == null) {
            throw new IllegalStateException(
                "hypopg extension is not installed/enabled on this database. "
                    + "Install it (`brew install hypopg`) and run `CREATE EXTENSION hypopg;` first."
            );
        }
    }

    private Set<String> loadValidTables() {
        return db.executeColumn(
            "SELECT lower(table_name) FROM information_schema.tables WHERE table_schema = 'public';"
        ).stream().collect(Collectors.toCollection(HashSet::new));
    }

    private Set<String> loadValidColumns() {
        return db.executeColumn(
            "SELECT lower(table_name) || '.' || lower(column_name) "
                + "FROM information_schema.columns WHERE table_schema = 'public';"
        ).stream().collect(Collectors.toCollection(HashSet::new));
    }

    public HypoEvaluationResult evaluate(CandidateIndex candidate, String sql) {
        String table = candidate.tableName().toLowerCase();

        assertKnownSchema(candidate, table);

        String quotedTable = quoteIdentifier(table);
        String quotedColumns = candidate.columns().stream()
            .map(String::toLowerCase)
            .map(HypoIndexEvaluator::quoteIdentifier)
            .collect(Collectors.joining(", "));

        try (ConnectionSession session = db.openSession()) {
            QueryPlan before = new QueryPlan(session.executeScalar("EXPLAIN (FORMAT JSON) " + sql));

            String createIndexSql = "CREATE INDEX ON " + quotedTable + " (" + quotedColumns + ")";
            session.executeScalar(
                "SELECT indexname FROM hypopg_create_index('" + createIndexSql.replace("'", "''") + "');"
            );

            QueryPlan after;

            try {
                after = new QueryPlan(session.executeScalar("EXPLAIN (FORMAT JSON) " + sql));
            } finally {
                session.executeScalar("SELECT hypopg_reset();");
            }

            double improvement = before.getTotalCost() == 0
                ? 0.0
                : 100.0 * (before.getTotalCost() - after.getTotalCost()) / before.getTotalCost();

            return new HypoEvaluationResult(
                candidate,
                before.getNodeType(),
                after.getNodeType(),
                before.getTotalCost(),
                after.getTotalCost(),
                improvement
            );
        }
    }

    private void assertKnownSchema(CandidateIndex candidate, String lowercaseTable) {
        if (!validTables.contains(lowercaseTable)) {
            throw new IllegalArgumentException("Unknown table: " + candidate.tableName());
        }

        for (String column : candidate.columns()) {
            String key = lowercaseTable + "." + column.toLowerCase();

            if (!validColumns.contains(key)) {
                throw new IllegalArgumentException("Unknown column: " + candidate.tableName() + "." + column);
            }
        }
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
