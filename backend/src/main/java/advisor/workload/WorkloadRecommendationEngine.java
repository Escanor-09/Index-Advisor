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
        return chooseIndexSet(allResults, IndexSetBudget.UNLIMITED);
    }

    /**
     * Same greedy algorithm, with two additional stopping conditions checked
     * right after the existing marginal-score bar: a max index count, and a
     * cumulative storage budget. Deliberately doesn't hunt for a smaller
     * candidate that might still fit once the greedy-best one doesn't —
     * stops there, the same way the existing marginal-score check already
     * stops rather than searching for a lesser candidate that still clears
     * it. A disclosed simplicity trade-off, not an oversight: it keeps the
     * "always take the single best, stop the moment it's disqualified"
     * invariant true for every stopping reason, not just some of them.
     */
    public static List<Recommendation> chooseIndexSet(List<WorkloadEvaluationResult> allResults, IndexSetBudget budget) {
        List<WorkloadEvaluationResult> remaining = new ArrayList<>(allResults);
        Set<String> coveredQueries = new HashSet<>();
        List<Recommendation> chosen = new ArrayList<>();
        long cumulativeStorageBytes = 0;

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

            if (chosen.size() >= budget.maxIndexCount()) {
                break;
            }

            long bestSizeBytes = indexSizeBytesOf(best);

            if (cumulativeStorageBytes + bestSizeBytes > budget.maxStorageBytes()) {
                break;
            }

            chosen.add(toRecommendation(best));
            cumulativeStorageBytes += bestSizeBytes;

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

    /**
     * Same candidate (same table + columns) is re-created once per applicable query it
     * was evaluated against, so its measured size should be effectively identical each
     * time — the first "helped" sample is representative. Guaranteed non-empty for any
     * result that cleared MIN_MARGINAL_SCORE, since that requires at least one query
     * with improvementPercent >= MIN_QUERY_IMPROVEMENT_PERCENT contributing to the sum
     * — the same condition that populates "helped" below. Falls back to 0 rather than
     * throwing for a candidate that never clears the bar, so this is also safe to call
     * from the budget check in chooseIndexSet() before a candidate is confirmed chosen.
     */
    private static long indexSizeBytesOf(WorkloadEvaluationResult result) {
        return result.queryEvaluations().stream()
            .filter(qe -> qe.result().improvementPercent() >= MIN_QUERY_IMPROVEMENT_PERCENT)
            .findFirst()
            .map(qe -> qe.result().indexSizeBytes())
            .orElse(0L);
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
            : helped.get(0).result().observedEffect()
                + (helped.size() > 1 ? " (representative; varies per query)" : "");

        long indexSizeBytes = indexSizeBytesOf(result);

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
