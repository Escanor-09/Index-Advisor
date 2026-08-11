package advisor.workload;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import advisor.recommendation.Recommendation;

/**
 * Chooses an index SET for the whole workload, greedily: repeatedly pick
 * the candidate with the best remaining marginal contribution (summed
 * improvement across queries not already well-served by a previously
 * chosen candidate), stop once nothing left clears the bar. This is what
 * prevents recommending e.g. both products(category_id) and
 * products(category_id, status) when the first already covers everything
 * the second would have covered.
 */
public class WorkloadRecommendationEngine {

    // A query only counts as "helped" by a candidate if the improvement clears this bar.
    private static final double MIN_QUERY_IMPROVEMENT_PERCENT = 10.0;

    // Stop adding candidates once the best remaining marginal contribution drops below this.
    private static final double MIN_MARGINAL_SCORE = 10.0;

    private static final double HIGH_IMPACT_THRESHOLD = 50.0;
    private static final double MEDIUM_IMPACT_THRESHOLD = 20.0;

    public static List<Recommendation> chooseIndexSet(List<WorkloadEvaluationResult> allResults) {
        List<WorkloadEvaluationResult> remaining = new ArrayList<>(allResults);
        Set<String> coveredQueries = new HashSet<>();
        List<Recommendation> chosen = new ArrayList<>();

        while (!remaining.isEmpty()) {
            WorkloadEvaluationResult best = null;
            double bestMarginalScore = Double.NEGATIVE_INFINITY;

            for (WorkloadEvaluationResult candidateResult : remaining) {
                double marginalScore = marginalScore(candidateResult, coveredQueries);

                if (marginalScore > bestMarginalScore) {
                    bestMarginalScore = marginalScore;
                    best = candidateResult;
                }
            }

            if (best == null || bestMarginalScore < MIN_MARGINAL_SCORE) {
                break;
            }

            chosen.add(toRecommendation(best));

            for (QueryEvaluation qe : best.queryEvaluations()) {
                if (qe.result().improvementPercent() >= MIN_QUERY_IMPROVEMENT_PERCENT) {
                    coveredQueries.add(qe.sql());
                }
            }

            remaining.remove(best);
        }

        return chosen;
    }

    private static double marginalScore(WorkloadEvaluationResult candidateResult, Set<String> coveredQueries) {
        return candidateResult.queryEvaluations().stream()
            .filter(qe -> qe.result().improvementPercent() >= MIN_QUERY_IMPROVEMENT_PERCENT)
            .filter(qe -> !coveredQueries.contains(qe.sql()))
            .mapToDouble(qe -> qe.result().improvementPercent())
            .sum();
    }

    /** Reports the candidate's full true impact (every query it actually helped), not just its marginal credit. */
    private static Recommendation toRecommendation(WorkloadEvaluationResult result) {
        List<QueryEvaluation> helped = result.queryEvaluations().stream()
            .filter(qe -> qe.result().improvementPercent() >= MIN_QUERY_IMPROVEMENT_PERCENT)
            .toList();

        double avgImprovement = helped.stream()
            .mapToDouble(qe -> qe.result().improvementPercent())
            .average()
            .orElse(0.0);

        List<String> affectedQueries = helped.stream().map(QueryEvaluation::sql).toList();

        String reason = String.format(
            "Across the workload, this candidate measurably improved %d of %d applicable quer%s filtering on %s.",
            helped.size(),
            result.queryEvaluations().size(),
            result.queryEvaluations().size() == 1 ? "y" : "ies",
            result.candidate().toDdlColumnList()
        );

        String observedEffect = helped.isEmpty()
            ? "No consistent plan improvement observed"
            : helped.get(0).result().beforeNodeType() + " -> " + helped.get(0).result().afterNodeType()
                + (helped.size() > 1 ? " (representative; varies per query)" : "");

        // Same candidate (same table + columns) is re-created once per applicable query it was
        // evaluated against, so its measured size should be effectively identical each time —
        // any one sample is representative. Guaranteed non-empty: this method only runs for
        // candidates the greedy algorithm chose, which requires at least one entry in `helped`.
        long indexSizeBytes = helped.get(0).result().indexSizeBytes();

        return new Recommendation(
            result.candidate(),
            impactTier(avgImprovement),
            affectedQueries,
            reason,
            observedEffect,
            avgImprovement,
            indexSizeBytes,
            List.of(
                "Additional index storage: " + Recommendation.formatBytes(indexSizeBytes),
                "Additional write/maintenance cost (not measured)"
            )
        );
    }

    private static String impactTier(double improvementPercent) {
        if (improvementPercent >= HIGH_IMPACT_THRESHOLD) {
            return "HIGH";
        }
        if (improvementPercent >= MEDIUM_IMPACT_THRESHOLD) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
