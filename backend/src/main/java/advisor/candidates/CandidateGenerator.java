package advisor.candidates;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import advisor.parsing.JoinClause;
import advisor.parsing.ParsedQuery;
import advisor.parsing.TableColumn;

public class CandidateGenerator {

    /**
     * Per table touched by the query (the primary FROM table, and any
     * joined table the WHERE clause also filters on): one single-column
     * candidate per filter column on that table, plus the full composite
     * if there's more than one. Plus one single-column candidate per side
     * of every JOIN clause — indexing join columns is the classic use
     * case JOIN support exists to cover. A LinkedHashSet dedupes the case
     * where a join column is also independently WHERE-filtered (both
     * paths would otherwise propose the identical candidate), while
     * preserving first-seen order.
     */
    public static List<CandidateIndex> generate(ParsedQuery query) {
        LinkedHashSet<CandidateIndex> candidates = new LinkedHashSet<>();

        Map<String, List<String>> filterColumnsByTable = query.qualifiedFilterColumns().stream()
            .collect(Collectors.groupingBy(
                TableColumn::table,
                LinkedHashMap::new,
                Collectors.mapping(TableColumn::column, Collectors.toList())
            ));

        for (Map.Entry<String, List<String>> entry : filterColumnsByTable.entrySet()) {
            String table = entry.getKey();
            List<String> columns = entry.getValue().stream().distinct().sorted().toList();

            for (String column : columns) {
                candidates.add(new CandidateIndex(table, List.of(column)));
            }

            if (columns.size() > 1) {
                candidates.add(new CandidateIndex(table, columns));
            }
        }

        for (JoinClause join : query.joins()) {
            candidates.add(new CandidateIndex(join.leftTable(), List.of(join.leftColumn())));
            candidates.add(new CandidateIndex(join.rightTable(), List.of(join.rightColumn())));
        }

        Map<String, List<String>> orderByColumnsByTable = query.qualifiedOrderByColumns().stream()
            .collect(Collectors.groupingBy(
                TableColumn::table,
                LinkedHashMap::new,
                Collectors.mapping(TableColumn::column, Collectors.toList())
            ));

        for (Map.Entry<String, List<String>> entry : orderByColumnsByTable.entrySet()) {
            String table = entry.getKey();
            List<String> orderByColumns = entry.getValue().stream().distinct().sorted().toList();
            List<String> filterColumns = filterColumnsByTable.getOrDefault(table, List.of());

            for (String orderByColumn : orderByColumns) {
                // A single-column sort-avoidance candidate — only worth proposing if this
                // column isn't already covered by a filter-column candidate above.
                if (!filterColumns.contains(orderByColumn)) {
                    candidates.add(new CandidateIndex(table, List.of(orderByColumn)));
                }

                // Leading filter column + trailing sort column: lets Postgres use one index
                // to both satisfy the WHERE predicate and avoid a separate sort step.
                for (String filterColumn : filterColumns) {
                    if (!filterColumn.equals(orderByColumn)) {
                        candidates.add(new CandidateIndex(table, List.of(filterColumn, orderByColumn)));
                    }
                }
            }
        }

        return new ArrayList<>(candidates);
    }

    /** Generates per query, then dedupes across the whole workload (order of first appearance preserved). */
    public static List<CandidateIndex> generateForWorkload(List<ParsedQuery> queries) {
        LinkedHashSet<CandidateIndex> unique = new LinkedHashSet<>();

        for (ParsedQuery query : queries) {
            unique.addAll(generate(query));
        }

        return new ArrayList<>(unique);
    }
}
