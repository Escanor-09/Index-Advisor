package advisor.parsing;

/**
 * A simple two-table equi-join condition: leftTable.leftColumn = rightTable.rightColumn.
 * Only single-condition ON clauses of this exact shape are recognized (see
 * SqlParser) — multi-condition ON, non-equality joins, and 3+ table chains
 * are a known, documented scope boundary, not silently mishandled.
 */
public record JoinClause(String leftTable, String leftColumn, String rightTable, String rightColumn) {}
