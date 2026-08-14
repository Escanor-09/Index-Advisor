package advisor.workload;

/**
 * An explicit stopping condition for WorkloadRecommendationEngine.chooseIndexSet(),
 * independent of its existing marginal-improvement-score bar. Real index size is
 * already measured (EvaluationResult.indexSizeBytes, threaded through
 * Recommendation.indexSizeBytes) — this is what actually uses that number as a
 * budget, rather than only reporting it.
 *
 * Long.MAX_VALUE / Integer.MAX_VALUE mean "no limit", chosen over Optional fields
 * so a plain numeric comparison (cumulative + candidate > budget) works
 * unconditionally at every call site, with no null/empty-check branching.
 */
public record IndexSetBudget(long maxStorageBytes, int maxIndexCount) {

    public static final IndexSetBudget UNLIMITED = new IndexSetBudget(Long.MAX_VALUE, Integer.MAX_VALUE);

    public static IndexSetBudget storageBytes(long maxStorageBytes) {
        return new IndexSetBudget(maxStorageBytes, Integer.MAX_VALUE);
    }

    public static IndexSetBudget indexCount(int maxIndexCount) {
        return new IndexSetBudget(Long.MAX_VALUE, maxIndexCount);
    }
}
