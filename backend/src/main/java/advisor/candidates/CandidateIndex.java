package advisor.candidates;

import java.util.List;

import advisor.parsing.JoinClause;
import advisor.parsing.ParsedQuery;
import advisor.parsing.TableColumn;

public record CandidateIndex(String tableName, List<String> columns) {

    public String toDdlColumnList() {
        return String.join(", ", columns);
    }

    /**
     * Whether this candidate could plausibly help the given query. Two ways
     * to qualify:
     * (1) every column this candidate covers is actually WHERE-filtered on
     *     this table by the query (works for the primary table and any
     *     joined table, since ParsedQuery's filter columns are
     *     table-attributed) — a composite candidate needs all its columns
     *     present, a simple subset check rather than modelling Postgres's
     *     leftmost-prefix rule in full (single-column candidates already
     *     separately cover the "just one column" case);
     * (2) it's a single-column candidate matching either side of a JOIN
     *     clause on this table — indexing join columns is the classic
     *     use case JOIN support exists to cover, and a join column isn't
     *     necessarily also a WHERE filter column.
     */
    public boolean appliesTo(ParsedQuery query) {
        List<String> filterColumnsOnThisTable = query.qualifiedFilterColumns().stream()
            .filter(tc -> tc.table().equals(tableName))
            .map(TableColumn::column)
            .toList();

        if (filterColumnsOnThisTable.containsAll(columns)) {
            return true;
        }

        if (columns.size() == 1) {
            String onlyColumn = columns.get(0);

            for (JoinClause join : query.joins()) {
                boolean matchesLeftSide = tableName.equals(join.leftTable()) && onlyColumn.equals(join.leftColumn());
                boolean matchesRightSide = tableName.equals(join.rightTable()) && onlyColumn.equals(join.rightColumn());

                if (matchesLeftSide || matchesRightSide) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public String toString() {
        return tableName + "(" + toDdlColumnList() + ")";
    }
}
