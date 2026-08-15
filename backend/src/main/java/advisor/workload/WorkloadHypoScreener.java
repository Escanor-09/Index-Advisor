package advisor.workload;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import advisor.candidates.CandidateIndex;
import advisor.database.PostgresManager;
import advisor.database.PostgresManager.ConnectionSession;
import advisor.evaluation.HypoEvaluationResult;
import advisor.evaluation.HypoIndexEvaluator;

/**
 * The first phase of the two-phase workload pipeline: a cheap HypoPG pass
 * over every candidate, deciding which are even worth the real
 * CREATE/DROP INDEX cost of full validation via WorkloadIndexEvaluator.
 * HypoIndexEvaluator was built and cross-validated against real
 * measurements a while ago but never wired into a live pipeline until now
 * — this class is that wiring.
 *
 * A candidate is promoted the moment HypoPG predicts it clears
 * SCREEN_THRESHOLD_PERCENT for *any* applicable query — short-circuiting
 * there is deliberate: screening only needs a yes/no "worth real
 * validation" answer, not the full picture WorkloadIndexEvaluator computes
 * afterward for every applicable query.
 */
public class WorkloadHypoScreener {

    // Mirrors WorkloadRecommendationEngine.MIN_QUERY_IMPROVEMENT_PERCENT: a candidate
    // only earns real validation if HypoPG predicts it could clear the same bar a
    // real result would need to clear to ever actually be recommended.
    private static final double SCREEN_THRESHOLD_PERCENT = 10.0;

    private final HypoIndexEvaluator hypoEvaluator;

    public WorkloadHypoScreener(PostgresManager db) {
        this.hypoEvaluator = new HypoIndexEvaluator(db);
    }

    /** Every candidate HypoPG predicts could help at least one applicable query, in original order. */
    public List<CandidateIndex> screen(List<CandidateIndex> candidates, WorkloadProfile profile) {
        Set<CandidateIndex> promoted = new LinkedHashSet<>();

        try (ConnectionSession session = hypoEvaluator.openSession()) {
            for (CandidateIndex candidate : candidates) {
                if (promising(candidate, profile, session)) {
                    promoted.add(candidate);
                }
            }
        }

        return new ArrayList<>(promoted);
    }

    private boolean promising(CandidateIndex candidate, WorkloadProfile profile, ConnectionSession session) {
        for (WorkloadQuery wq : profile.queries()) {
            if (!candidate.appliesTo(wq.parsedQuery())) {
                continue;
            }

            HypoEvaluationResult result = hypoEvaluator.evaluate(candidate, wq.sql(), session);

            if (result.estimatedCostImprovementPercent() >= SCREEN_THRESHOLD_PERCENT) {
                return true;
            }
        }

        return false;
    }
}
