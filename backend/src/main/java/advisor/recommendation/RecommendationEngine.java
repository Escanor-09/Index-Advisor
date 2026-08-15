package advisor.recommendation;

import java.util.Comparator;
import java.util.List;

import advisor.evaluation.EvaluationResult;

public class RecommendationEngine {

    // Below this, the measured gain is noise-level and not worth the write/storage cost of an index.
    private static final double MIN_IMPROVEMENT_PERCENT = 10.0;

    private static final double HIGH_IMPACT_THRESHOLD = 50.0;
    private static final double MEDIUM_IMPACT_THRESHOLD = 20.0;

    /**
     * Single-query scope for now (Milestone 11/12): every recommendation is
     * scoped to the one query being analyzed. Milestone 13 (Workload
     * Advisor) is what aggregates this across many queries and many
     * candidates into a chosen index SET.
     */
    public static List<Recommendation> recommend(List<EvaluationResult> results, String sql) {
        return pruneDominated(results).stream()
            .filter(r -> r.improvementPercent() >= MIN_IMPROVEMENT_PERCENT)
            .sorted(Comparator.comparingDouble(EvaluationResult::improvementPercent).reversed())
            .map(r -> toRecommendation(r, sql))
            .toList();
    }

    /**
     * Drops any result whose candidate is strictly dominated by another
     * candidate on the same table: same-or-fewer columns, those columns a
     * leading prefix of the dominated one's, and an equal-or-better measured
     * improvement. A 3-column composite that helps no more than its own
     * 1-column prefix already does is pure extra write/storage cost with
     * zero additional benefit — Postgres can already use the shorter index's
     * leading columns for the exact same plan.
     */
    static List<EvaluationResult> pruneDominated(List<EvaluationResult> results) {
        return results.stream()
            .filter(candidate -> results.stream()
                .filter(other -> other != candidate)
                .noneMatch(other -> dominates(other, candidate)))
            .toList();
    }

    private static boolean dominates(EvaluationResult a, EvaluationResult b) {
        return a.candidate().tableName().equals(b.candidate().tableName())
            && isPrefix(a.candidate().columns(), b.candidate().columns())
            && a.improvementPercent() >= b.improvementPercent();
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

    private static Recommendation toRecommendation(EvaluationResult r, String sql) {
        String reason = "Query filters on " + r.candidate().toDdlColumnList()
            + "; this candidate showed a measurable improvement when evaluated against the real query plan.";

        String observedEffect = r.observedEffect();

        return new Recommendation(
            r.candidate(),
            impactTier(r.improvementPercent()),
            List.of(sql),
            reason,
            observedEffect,
            r.improvementPercent(),
            r.indexSizeBytes(),
            List.of(
                "Additional index storage: " + Recommendation.formatBytes(r.indexSizeBytes()),
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
