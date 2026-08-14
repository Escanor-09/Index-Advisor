package advisor.candidates;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import advisor.database.PostgresManager;

/**
 * Discovers indexes that already exist in the target schema (via pg_indexes)
 * so QueryAdvisor can skip proposing — and paying the real CREATE/DROP INDEX
 * cost of evaluating — a candidate that's redundant with something already
 * there. The most basic sanity check a real advisor needs: never recommend
 * what's already been built.
 *
 * indexdef parsing is a controlled string search, not a general SQL parser:
 * Postgres always schema-qualifies the table name in indexdef ("... ON
 * public.orders USING btree (...)"), and every index this project itself
 * creates (see IndexEvaluator) is a plain column list, never a functional
 * or partial index — so the last matching parens reliably bound the column
 * list for anything this class needs to reason about.
 */
public class ExistingIndexManager {

    private static final String SCHEMA_PREFIX = "public.";

    private final PostgresManager db;

    public ExistingIndexManager(PostgresManager db) {
        this.db = db;
    }

    public List<ExistingIndex> getExistingIndexes() {
        List<String> indexDefs = db.executeColumn(
            "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public';"
        );

        List<ExistingIndex> indexes = new ArrayList<>();

        for (String indexDef : indexDefs) {
            ExistingIndex parsed = parseIndexDef(indexDef);

            if (parsed != null) {
                indexes.add(parsed);
            }
        }

        return indexes;
    }

    /** True if candidate's columns are, in order, a leading prefix of some existing index on the same table. */
    public boolean isCoveredByExistingIndex(CandidateIndex candidate, List<ExistingIndex> existingIndexes) {
        for (ExistingIndex existing : existingIndexes) {
            if (!existing.tableName().equalsIgnoreCase(candidate.tableName())) {
                continue;
            }

            if (isPrefix(candidate.columns(), existing.columns())) {
                return true;
            }
        }

        return false;
    }

    private static ExistingIndex parseIndexDef(String indexDef) {
        int tableStart = indexDef.indexOf(SCHEMA_PREFIX);
        int tableEnd = indexDef.indexOf(" USING", tableStart);

        if (tableStart < 0 || tableEnd < 0) {
            return null;
        }

        String tableName = indexDef.substring(tableStart + SCHEMA_PREFIX.length(), tableEnd);

        int leftParen = indexDef.lastIndexOf('(');
        int rightParen = indexDef.lastIndexOf(')');

        if (leftParen < 0 || rightParen < leftParen) {
            return null;
        }

        List<String> columns = Arrays.stream(indexDef.substring(leftParen + 1, rightParen).split(","))
            .map(String::trim)
            .toList();

        return new ExistingIndex(tableName, columns);
    }

    private static boolean isPrefix(List<String> small, List<String> large) {
        if (small.size() > large.size()) {
            return false;
        }

        for (int i = 0; i < small.size(); i++) {
            if (!small.get(i).equalsIgnoreCase(large.get(i))) {
                return false;
            }
        }

        return true;
    }
}
