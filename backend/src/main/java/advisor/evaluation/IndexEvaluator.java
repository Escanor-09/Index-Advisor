package advisor.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import advisor.candidates.CandidateIndex;
import advisor.database.PostgresManager;
import advisor.planning.QueryPlan;

/**
 * Measures a candidate index's real impact: baseline EXPLAIN ANALYZE,
 * CREATE INDEX, EXPLAIN ANALYZE again, DROP INDEX, compare. Never leaves
 * an index behind, and refuses to run against anything but the local
 * sandbox database.
 *
 * Every candidate's table/columns are validated against the real schema
 * before any DDL is built, and every identifier is double-quoted in the
 * generated SQL. Table/column names reach this class after being extracted
 * by SqlParser from caller-supplied SQL text — currently that text is
 * always internally generated, but as of Milestone 14 it can come from an
 * HTTP caller, and DDL identifiers can't be parameterized via JDBC
 * PreparedStatement (only values can). Without these two checks, a crafted
 * "column name" containing a semicolon could be executed as a second
 * statement — the Postgres JDBC driver's simple query protocol (used by
 * Statement.execute) allows multiple ;-separated statements in one call.
 *
 * Both the before and after measurement use the same warm-up + repeated
 * sampling protocol (see measureStable). This exists because of a real bug
 * found during Milestone 14 testing: CREATE INDEX itself requires a full
 * table scan to build the index, which warms the OS/Postgres page cache
 * regardless of whether the resulting index is ever used by the planner —
 * so a single-sample "after" measurement could look faster purely from
 * that cache warm-up, not from the index doing anything (observed live:
 * products(status) scored ~40% "improvement" while observedEffect stayed
 * "Seq Scan -> Seq Scan", i.e. the plan never changed). Warming up
 * identically and taking the median of several runs on both sides removes
 * that asymmetry.
 *
 * Index names include a per-call unique suffix, not just table+columns.
 * Found by testing concurrent HTTP requests after adding HikariCP pooling
 * (Priority Improvement — HikariCP): two requests evaluating the same
 * candidate concurrently both built the identical deterministic name and
 * raced on CREATE INDEX — one succeeded, one failed with "relation already
 * exists". The bug always existed structurally; concurrent load just
 * wasn't achievable before pooling removed per-call connection latency.
 */
public class IndexEvaluator {

    private static final int WARMUP_RUNS = 1;
    private static final int MEASURED_RUNS = 3;

    private final PostgresManager db;
    private final Set<String> validTables;
    private final Set<String> validColumns; // "table.column", both lowercase
    private final AtomicLong evaluationCounter = new AtomicLong();

    public IndexEvaluator(PostgresManager db) {
        this.db = db;
        assertSafeDatabase();
        this.validTables = loadValidTables();
        this.validColumns = loadValidColumns();
    }

    private void assertSafeDatabase() {
        String currentDb = db.executeScalar("SELECT current_database();");

        if (!"index_advisor".equals(currentDb)) {
            throw new IllegalStateException(
                "Refusing to run index experiments against database '" + currentDb + "'. "
                    + "IndexEvaluator only runs on the local 'index_advisor' sandbox, "
                    + "never the shared Supabase 'ecom' database."
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

    public EvaluationResult evaluate(CandidateIndex candidate, String sql) {
        String table = candidate.tableName().toLowerCase();

        assertKnownSchema(candidate, table);

        String indexName = buildIndexName(candidate, table);
        String quotedIndexName = quoteIdentifier(indexName);
        String quotedTable = quoteIdentifier(table);
        String quotedColumns = candidate.columns().stream()
            .map(String::toLowerCase)
            .map(IndexEvaluator::quoteIdentifier)
            .collect(Collectors.joining(", "));

        // Defensive: clean up any index left behind by a previous crashed run.
        db.executeUpdate("DROP INDEX IF EXISTS " + quotedIndexName + ";");

        QueryPlan before = measureStable(sql);

        db.executeUpdate("CREATE INDEX " + quotedIndexName + " ON " + quotedTable + " (" + quotedColumns + ");");

        QueryPlan after;
        long indexSizeBytes;

        try {
            indexSizeBytes = measureIndexSizeBytes(indexName);
            after = measureStable(sql);
        } finally {
            db.executeUpdate("DROP INDEX IF EXISTS " + quotedIndexName + ";");
        }

        double improvement = computeImprovement(before, after);

        return new EvaluationResult(
            candidate,
            before.getNodeType(),
            after.getNodeType(),
            before.getTotalCost(),
            after.getTotalCost(),
            before.getExecutionTime(),
            after.getExecutionTime(),
            improvement,
            indexSizeBytes
        );
    }

    /**
     * Pure, DB-free so it's directly unit-testable (see IndexEvaluatorLogicTest).
     * If Postgres kept the exact same plan shape throughout the whole tree,
     * there is no mechanism by which the query structurally got faster — any
     * remaining positive delta is residual cache/timing noise (see
     * measureStable's javadoc), not a real effect, and must not be credited.
     *
     * Compares the FULL plan shape (QueryPlan.getAllNodeTypes()), not just
     * the root node type. Found live testing JOIN support: for a join query
     * the root node is the join STRATEGY (e.g. Nested Loop), which can stay
     * identical while a nested child scan legitimately changes (an inner
     * Seq Scan becoming an Index Scan) — comparing only the root wrongly
     * clamped a genuine ~93% improvement to zero in exactly that case.
     * Single-table plans have no nested "Plans" array, so getAllNodeTypes()
     * there is just [root type] — this fix is behavior-preserving for every
     * single-table case already covered by IndexEvaluatorLogicTest.
     */
    static double computeImprovement(QueryPlan before, QueryPlan after) {
        double improvement = before.getExecutionTime() == 0
            ? 0.0
            : 100.0 * (before.getExecutionTime() - after.getExecutionTime()) / before.getExecutionTime();

        if (before.getAllNodeTypes().equals(after.getAllNodeTypes())) {
            improvement = Math.min(improvement, 0.0);
        }

        return improvement;
    }

    /**
     * Actual on-disk size of the just-created index, in bytes. Measured while
     * the index still exists (between CREATE and DROP) so the number is real,
     * not estimated. indexName is unquoted here deliberately — pg_relation_size
     * takes a regclass, and this name was already built from schema-validated,
     * lowercased identifiers (see assertKnownSchema/buildIndexName), so no
     * further quoting/escaping is needed for this read-only lookup.
     */
    private long measureIndexSizeBytes(String indexName) {
        String bytes = db.executeScalar("SELECT pg_relation_size('" + indexName + "'::regclass);");
        return Long.parseLong(bytes);
    }

    /**
     * Runs WARMUP_RUNS discarded executions (to bring the cache to a
     * consistent state), then MEASURED_RUNS real ones, returning the plan
     * with the median execution time. Node type and estimated cost don't
     * vary run to run for a fixed query/schema state, so the median-time
     * sample's other fields are exactly as representative as any other
     * sample's — only execution time is noisy enough to need this.
     */
    private QueryPlan measureStable(String sql) {
        for (int i = 0; i < WARMUP_RUNS; i++) {
            db.explainAnalyze(sql);
        }

        List<QueryPlan> samples = new ArrayList<>();

        for (int i = 0; i < MEASURED_RUNS; i++) {
            samples.add(new QueryPlan(db.explainAnalyze(sql)));
        }

        samples.sort(Comparator.comparingDouble(QueryPlan::getExecutionTime));

        return samples.get(samples.size() / 2);
    }

    /** The actual enforcement point: only identifiers that exist in the real schema may ever reach DDL text. */
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

    private String buildIndexName(CandidateIndex candidate, String lowercaseTable) {
        String columns = candidate.columns().stream()
            .map(String::toLowerCase)
            .collect(Collectors.joining("_"));

        return "idx_experiment_" + lowercaseTable + "_" + columns + "_" + evaluationCounter.incrementAndGet();
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
