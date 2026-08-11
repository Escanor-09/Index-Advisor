package advisor.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

import advisor.planning.QueryPlan;

/**
 * Tests IndexEvaluator.computeImprovement in isolation (no database) — it
 * was extracted specifically to make this bug directly, fast, unit-testable.
 */
class IndexEvaluatorLogicTest {

    private static QueryPlan planWith(String nodeType, double executionTime) {
        String json = "[{\"Plan\": {\"Node Type\": \"" + nodeType + "\", \"Total Cost\": 1.0}, "
            + "\"Execution Time\": " + executionTime + ", \"Planning Time\": 0.1}]";

        return new QueryPlan(json);
    }

    /** Root stays "Nested Loop" in both; the given inner-scan node type is what changes. */
    private static QueryPlan nestedLoopPlanWith(String innerScanNodeType, double executionTime) {
        String json = "[{\"Plan\": {\"Node Type\": \"Nested Loop\", \"Total Cost\": 1.0, \"Plans\": ["
            + "{\"Node Type\": \"Index Scan\"},"
            + "{\"Node Type\": \"" + innerScanNodeType + "\"}"
            + "]}, \"Execution Time\": " + executionTime + ", \"Planning Time\": 0.1}]";

        return new QueryPlan(json);
    }

    @Test
    void planChanged_realImprovementCredited() {
        QueryPlan before = planWith("Seq Scan", 10.0);
        QueryPlan after = planWith("Bitmap Heap Scan", 1.0);

        double improvement = IndexEvaluator.computeImprovement(before, after);

        assertThat(improvement).isCloseTo(90.0, within(0.01));
    }

    /**
     * Regression test for the live-observed bug (Milestone 14 testing):
     * products(status) scored ~40% "improvement" while the plan stayed
     * "Seq Scan -> Seq Scan" — CREATE INDEX's own table-scan had warmed the
     * cache, making the "after" measurement look faster for reasons that
     * had nothing to do with the index.
     */
    @Test
    void planUnchanged_positiveDeltaClampedToZero_regressionForCacheWarmingBug() {
        QueryPlan before = planWith("Seq Scan", 0.889);
        QueryPlan after = planWith("Seq Scan", 0.533); // ~40% "faster" despite identical strategy

        double improvement = IndexEvaluator.computeImprovement(before, after);

        assertThat(improvement).isLessThanOrEqualTo(0.0);
    }

    @Test
    void planUnchanged_realNegativeDeltaStillReportedHonestly() {
        // e.g. CREATE INDEX write overhead making the query marginally slower — a real,
        // if small, negative effect that should still be visible, not hidden by the clamp.
        QueryPlan before = planWith("Seq Scan", 0.889);
        QueryPlan after = planWith("Seq Scan", 0.939);

        double improvement = IndexEvaluator.computeImprovement(before, after);

        assertThat(improvement).isNegative();
    }

    @Test
    void zeroBeforeExecutionTime_doesNotDivideByZero() {
        QueryPlan before = planWith("Seq Scan", 0.0);
        QueryPlan after = planWith("Seq Scan", 0.0);

        assertThat(IndexEvaluator.computeImprovement(before, after)).isEqualTo(0.0);
    }

    /**
     * Regression test for a real bug found live during JOIN support testing:
     * "SELECT * FROM customers c JOIN orders o ON c.id = o.customer_id WHERE c.id = 500"
     * kept "Nested Loop" as the root node both before and after creating
     * orders(customer_id) — only the nested inner scan changed, from Seq Scan
     * to Bitmap Heap Scan (verified by hand: baseline ~2.4ms, with the index
     * ~0.16ms, a genuine ~93% improvement). The first version of this clamp
     * compared only the root node type, saw "Nested Loop" == "Nested Loop",
     * and wrongly suppressed the entire improvement — silently defeating
     * exactly the scenario JOIN support exists to handle.
     */
    @Test
    void nestedLoopWithChangedInnerScan_improvementCredited_regressionForJoinClampBug() {
        QueryPlan before = nestedLoopPlanWith("Seq Scan", 2.438);
        QueryPlan after = nestedLoopPlanWith("Bitmap Heap Scan", 0.159);

        double improvement = IndexEvaluator.computeImprovement(before, after);

        assertThat(improvement).isGreaterThan(80.0);
    }

    @Test
    void nestedLoopWithIdenticalInnerScan_stillClamped() {
        QueryPlan before = nestedLoopPlanWith("Seq Scan", 2.438);
        QueryPlan after = nestedLoopPlanWith("Seq Scan", 2.100); // no plan change anywhere — noise

        double improvement = IndexEvaluator.computeImprovement(before, after);

        assertThat(improvement).isLessThanOrEqualTo(0.0);
    }
}
