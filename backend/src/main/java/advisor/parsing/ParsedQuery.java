package advisor.parsing;

import java.util.List;

/**
 * tableName/filterColumns() are kept backward-compatible with the
 * pre-JOIN-support shape (primary table, unqualified WHERE columns) so
 * every caller written before JOIN support — CandidateGenerator's filter
 * logic, WorkloadAdvisor's frequency counting, existing tests — kept
 * working unchanged. qualifiedFilterColumns/joins are the new,
 * table-attributed data JOIN-aware code (CandidateGenerator's join-column
 * generation, CandidateIndex.appliesTo) reads instead.
 *
 * qualifiedOrderByColumns follows the same table-attributed shape as
 * qualifiedFilterColumns — it exists to let CandidateGenerator propose
 * sort-avoidance indexes (a query that filters on one column and sorts by
 * another benefits from a composite index covering both, even though the
 * sort column is never itself a WHERE predicate).
 */
public record ParsedQuery(
    String tableName,
    List<TableColumn> qualifiedFilterColumns,
    List<JoinClause> joins,
    List<TableColumn> qualifiedOrderByColumns
) {

    /** Convenience factory for the common single-table, no-join, no-order-by case (tests, simple callers). */
    public static ParsedQuery singleTable(String tableName, List<String> filterColumns) {
        List<TableColumn> qualified = filterColumns.stream()
            .map(column -> new TableColumn(tableName, column))
            .toList();

        return new ParsedQuery(tableName, qualified, List.of(), List.of());
    }

    /** WHERE columns on just the primary table, unqualified — the pre-JOIN-support contract. */
    public List<String> filterColumns() {
        return qualifiedFilterColumns.stream()
            .filter(tc -> tc.table().equals(tableName))
            .map(TableColumn::column)
            .distinct()
            .sorted()
            .toList();
    }
}
