package advisor.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Stream;

import advisor.QueryAdvisor;
import advisor.evaluation.EvaluationResult;
import advisor.parsing.JoinClause;
import advisor.parsing.ParsedQuery;
import advisor.parsing.TableColumn;
import advisor.recommendation.Recommendation;

/**
 * Shapes QueryAdvisor's internal result (parsed query + every evaluated
 * candidate + the filtered recommendations) into what the frontend renders:
 * a single best pick, a full candidate table, and a query-analysis
 * breakdown. originalCost/bestCost are real measured execution times in
 * milliseconds (EvaluationResult.beforeExecutionTime/afterExecutionTime),
 * not planner cost estimates — the whole reason this engine measures rather
 * than estimates.
 */
public record AnalyzeResponse(
    String bestIndex,
    double originalCost,
    double bestCost,
    double improvement,
    List<CandidateView> candidates,
    QueryAnalysisView analysis
) {

    public record CandidateView(String index, double cost, double improvement) {}

    public record QueryAnalysisView(
        List<String> tables,
        List<ColumnView> filterColumns,
        List<ColumnView> joinColumns,
        List<ColumnView> orderByColumns
    ) {}

    public record ColumnView(String table, String column) {}

    public static AnalyzeResponse from(QueryAdvisor.SingleQueryAnalysis analysis) {
        List<CandidateView> candidateViews = analysis.allResults().stream()
            .map(r -> new CandidateView(r.candidate().toString(), r.afterExecutionTime(), r.improvementPercent()))
            .toList();

        Recommendation best = analysis.recommendations().isEmpty() ? null : analysis.recommendations().get(0);

        String bestIndex = best == null ? "" : best.candidate().toString();
        double improvement = best == null ? 0.0 : best.estimatedImprovementPercent();

        double[] bestTimings = best == null ? new double[] {0.0, 0.0} : findTimings(analysis.allResults(), best);

        return new AnalyzeResponse(
            bestIndex,
            bestTimings[0],
            bestTimings[1],
            improvement,
            candidateViews,
            toAnalysisView(analysis.parsedQuery())
        );
    }

    // Recommendation doesn't carry raw before/after timings (see Recommendation.java) —
    // look up the EvaluationResult that produced the winning recommendation instead.
    private static double[] findTimings(List<EvaluationResult> allResults, Recommendation best) {
        for (EvaluationResult r : allResults) {
            if (r.candidate().equals(best.candidate())) {
                return new double[] {r.beforeExecutionTime(), r.afterExecutionTime()};
            }
        }

        return new double[] {0.0, 0.0};
    }

    private static QueryAnalysisView toAnalysisView(ParsedQuery parsed) {
        LinkedHashSet<String> tables = new LinkedHashSet<>();
        tables.add(parsed.tableName());

        for (JoinClause join : parsed.joins()) {
            tables.add(join.leftTable());
            tables.add(join.rightTable());
        }

        List<ColumnView> joinColumns = parsed.joins().stream()
            .flatMap(j -> Stream.of(
                new ColumnView(j.leftTable(), j.leftColumn()),
                new ColumnView(j.rightTable(), j.rightColumn())
            ))
            .toList();

        return new QueryAnalysisView(
            new ArrayList<>(tables),
            toColumnViews(parsed.qualifiedFilterColumns()),
            joinColumns,
            toColumnViews(parsed.qualifiedOrderByColumns())
        );
    }

    private static List<ColumnView> toColumnViews(List<TableColumn> columns) {
        return columns.stream().map(tc -> new ColumnView(tc.table(), tc.column())).toList();
    }
}
