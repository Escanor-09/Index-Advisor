package advisor.web;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import advisor.WorkloadQueryAdvisor;
import advisor.evaluation.EvaluationResult;
import advisor.recommendation.Recommendation;
import advisor.workload.QueryEvaluation;
import advisor.workload.WorkloadEvaluationResult;

/**
 * Shapes WorkloadQueryAdvisor's internal result (the full per-candidate,
 * per-query evaluation data plus the chosen index set) into what the
 * frontend renders: a chosen-index list with real per-index cost/coverage,
 * plus a workload-wide before/after summary. Mirrors AnalyzeResponse's
 * single-query design for the same reason — Recommendation is deliberately
 * a "decision" record (see RecommendationEngine's split from
 * EvaluationResult) and doesn't carry raw timings, so this reaches back
 * into the raw WorkloadEvaluationResult data to compute them.
 */
public record WorkloadAnalyzeResponse(
    List<IndexView> recommendedIndexes,
    double beforeCost,
    double afterCost,
    double improvement
) {

    public record IndexView(String index, double cost, double improvement, double coverage) {}

    public static WorkloadAnalyzeResponse from(WorkloadQueryAdvisor.WorkloadAnalysis analysis) {
        int totalQueries = analysis.profile().queries().size();

        List<IndexView> views = new ArrayList<>();
        List<Double> beforeAverages = new ArrayList<>();
        List<Double> afterAverages = new ArrayList<>();

        for (Recommendation recommendation : analysis.recommendations()) {
            WorkloadEvaluationResult matched = findMatching(analysis.allResults(), recommendation);

            if (matched == null) {
                continue;
            }

            Set<String> affected = new HashSet<>(recommendation.affectedQueries());

            List<EvaluationResult> helpedResults = matched.queryEvaluations().stream()
                .filter(qe -> affected.contains(qe.sql()))
                .map(QueryEvaluation::result)
                .toList();

            double avgBefore = average(helpedResults.stream().mapToDouble(EvaluationResult::beforeExecutionTime));
            double avgAfter = average(helpedResults.stream().mapToDouble(EvaluationResult::afterExecutionTime));
            double coverage = totalQueries == 0 ? 0.0 : 100.0 * recommendation.affectedQueries().size() / totalQueries;

            views.add(new IndexView(
                recommendation.candidate().toString(),
                avgAfter,
                recommendation.estimatedImprovementPercent(),
                coverage
            ));

            beforeAverages.add(avgBefore);
            afterAverages.add(avgAfter);
        }

        double overallBefore = average(beforeAverages.stream().mapToDouble(Double::doubleValue));
        double overallAfter = average(afterAverages.stream().mapToDouble(Double::doubleValue));
        double overallImprovement = overallBefore == 0.0
            ? 0.0
            : 100.0 * (overallBefore - overallAfter) / overallBefore;

        return new WorkloadAnalyzeResponse(views, overallBefore, overallAfter, overallImprovement);
    }

    private static WorkloadEvaluationResult findMatching(List<WorkloadEvaluationResult> allResults, Recommendation recommendation) {
        return allResults.stream()
            .filter(r -> r.candidate().equals(recommendation.candidate()))
            .findFirst()
            .orElse(null);
    }

    private static double average(java.util.stream.DoubleStream stream) {
        return stream.average().orElse(0.0);
    }
}
