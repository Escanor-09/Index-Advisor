package advisor;

import java.util.List;

import advisor.candidates.CandidateGenerator;
import advisor.candidates.CandidateIndex;
import advisor.database.PostgresManager;
import advisor.parsing.ParsedQuery;
import advisor.recommendation.Recommendation;
import advisor.workload.WorkloadAdvisor;
import advisor.workload.WorkloadEvaluationResult;
import advisor.workload.WorkloadHypoScreener;
import advisor.workload.WorkloadIndexEvaluator;
import advisor.workload.WorkloadProfile;
import advisor.workload.WorkloadQuery;
import advisor.workload.WorkloadRecommendationEngine;

/**
 * Top-level workload orchestrator: ingest -> profile -> generate
 * candidates -> screen cheaply with HypoPG -> validate only the promising
 * ones for real -> choose an index SET (not just each query's
 * individually-best answer).
 *
 * The HypoPG screening step means this now has a hard runtime dependency
 * on the hypopg extension being installed on the target database (see
 * HypoIndexEvaluator's assertHypoPgAvailable()) — previously HypoPG was
 * only ever exercised by Main.java's standalone demo, never required for
 * an ordinary workload request to succeed.
 */
public class WorkloadQueryAdvisor {

    private final PostgresManager db;

    public WorkloadQueryAdvisor(PostgresManager db) {
        this.db = db;
    }

    public List<Recommendation> analyzeWorkload(List<String> sqlQueries) {
        return analyzeDetailed(sqlQueries).recommendations();
    }

    /** Exposes the profile and every candidate's full per-query evaluation, not just the chosen set — the HTTP layer needs this to compute real per-index cost/coverage numbers. */
    public WorkloadAnalysis analyzeDetailed(List<String> sqlQueries) {
        validateWorkload(sqlQueries);

        WorkloadProfile profile = WorkloadAdvisor.analyzeWorkload(sqlQueries, db);

        List<ParsedQuery> allParsed = profile.queries().stream().map(WorkloadQuery::parsedQuery).toList();
        List<CandidateIndex> candidates = CandidateGenerator.generateForWorkload(allParsed);

        validateCandidateCount(candidates);

        WorkloadHypoScreener screener = new WorkloadHypoScreener(db);
        List<CandidateIndex> promoted = screener.screen(candidates, profile);

        WorkloadIndexEvaluator evaluator = new WorkloadIndexEvaluator(db);
        List<WorkloadEvaluationResult> results = evaluator.evaluateAll(promoted, profile);

        List<Recommendation> recommendations = WorkloadRecommendationEngine.chooseIndexSet(results);

        return new WorkloadAnalysis(profile, results, recommendations, candidates.size(), promoted.size());
    }

    /**
     * totalCandidates/screenedCandidates let callers (the benchmark harness,
     * eventually API responses) see how much real-DDL work the HypoPG
     * screening pass actually avoided, not just the final recommendations.
     */
    public record WorkloadAnalysis(
        WorkloadProfile profile,
        List<WorkloadEvaluationResult> allResults,
        List<Recommendation> recommendations,
        int totalCandidates,
        int screenedCandidates
    ) {}

    /**
     * Package-private (not private) so both are directly unit-testable —
     * no PostgresManager needed. Rejects fast, before WorkloadAdvisor's
     * profiling step runs a real EXPLAIN ANALYZE per query. See ApiLimits.
     */
    static void validateWorkload(List<String> sqlQueries) {
        if (sqlQueries == null || sqlQueries.isEmpty()) {
            throw new IllegalArgumentException("Workload must contain at least one query.");
        }

        if (sqlQueries.size() > ApiLimits.MAX_WORKLOAD_QUERIES) {
            throw new IllegalArgumentException(
                "Workload contains " + sqlQueries.size() + " queries, exceeding the maximum of "
                    + ApiLimits.MAX_WORKLOAD_QUERIES + " per request."
            );
        }

        for (String sql : sqlQueries) {
            if (sql != null && sql.length() > ApiLimits.MAX_SQL_LENGTH) {
                throw new IllegalArgumentException(
                    "A query in this workload is " + sql.length() + " characters, exceeding the maximum of "
                        + ApiLimits.MAX_SQL_LENGTH + "."
                );
            }
        }
    }

    /**
     * A second, later gate: query count alone doesn't bound candidate
     * count, since a handful of queries touching many distinct columns can
     * still generate a large candidate set. Runs after profiling (which
     * validateWorkload's query-count check already bounded) but before the
     * genuinely expensive phase — HypoPG screening, then real DDL
     * evaluation of whatever's promoted.
     */
    static void validateCandidateCount(List<CandidateIndex> candidates) {
        if (candidates.size() > ApiLimits.MAX_CANDIDATES) {
            throw new IllegalArgumentException(
                "This workload generates " + candidates.size() + " distinct index candidates, exceeding the "
                    + "maximum of " + ApiLimits.MAX_CANDIDATES + " per request. Reduce the number of distinct "
                    + "filter/join/order-by columns across the workload, or split it into smaller requests."
            );
        }
    }
}
