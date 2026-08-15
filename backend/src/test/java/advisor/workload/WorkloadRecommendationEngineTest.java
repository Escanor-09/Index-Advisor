package advisor.workload;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import advisor.candidates.CandidateIndex;
import advisor.evaluation.EvaluationResult;
import advisor.recommendation.Recommendation;

/**
 * Encodes the real, live-verified behavior from Milestone 13: the whole
 * point of workload-level (vs. per-query) recommendation is catching
 * redundancy a per-query view structurally cannot see. These numbers are
 * the actual measured values from that milestone's real run, not invented.
 */
class WorkloadRecommendationEngineTest {

    private static final String Q1 = "SELECT * FROM products WHERE category_id = 25;";
    private static final String Q2 = "SELECT * FROM products WHERE category_id = 100;";
    private static final String Q4 = "SELECT * FROM products WHERE category_id = 25 AND status = 'active';";

    private static EvaluationResult evalResult(CandidateIndex candidate, double improvement, String afterType) {
        return new EvaluationResult(candidate, "Seq Scan", afterType, "Seq Scan -> " + afterType, 400.0, 10.0, 1.0, 0.1, improvement, 8192L);
    }

    private static EvaluationResult evalResultWithSize(CandidateIndex candidate, double improvement, long indexSizeBytes) {
        return new EvaluationResult(candidate, "Seq Scan", "Index Scan", "Seq Scan -> Index Scan", 400.0, 10.0, 1.0, 0.1, improvement, indexSizeBytes);
    }

    @Test
    void widerCandidateWinsAndMakesOverlappingCompositeRedundant() {
        CandidateIndex categoryId = new CandidateIndex("products", List.of("category_id"));
        CandidateIndex composite = new CandidateIndex("products", List.of("category_id", "status"));
        CandidateIndex status = new CandidateIndex("products", List.of("status"));

        // category_id alone is evaluated against all 3 queries that filter on it.
        WorkloadEvaluationResult categoryIdResult = new WorkloadEvaluationResult(
            categoryId,
            List.of(
                new QueryEvaluation(Q1, evalResult(categoryId, 92.0, "Bitmap Heap Scan")),
                new QueryEvaluation(Q2, evalResult(categoryId, 91.0, "Bitmap Heap Scan")),
                new QueryEvaluation(Q4, evalResult(categoryId, 95.8, "Bitmap Heap Scan"))
            ),
            92.0 + 91.0 + 95.8
        );

        // The composite only applies to Q4 (it needs both columns filtered).
        WorkloadEvaluationResult compositeResult = new WorkloadEvaluationResult(
            composite,
            List.of(new QueryEvaluation(Q4, evalResult(composite, 96.2, "Index Scan"))),
            96.2
        );

        // status alone never clears the threshold (real measured value: -6.8%).
        WorkloadEvaluationResult statusResult = new WorkloadEvaluationResult(
            status,
            List.of(new QueryEvaluation(Q4, evalResult(status, -6.8, "Seq Scan"))),
            -6.8
        );

        List<Recommendation> chosen = WorkloadRecommendationEngine.chooseIndexSet(
            List.of(categoryIdResult, compositeResult, statusResult)
        );

        // category_id wins the greedy comparison (covers 3 queries vs. the composite's 1),
        // and once chosen, the composite's only query is already covered — so the composite
        // is correctly excluded, even though in isolation (single-query view) it would have
        // been recommended too. This is the actual gap between per-query and workload-level
        // advice, verified live in Milestone 13.
        assertThat(chosen).hasSize(1);
        assertThat(chosen.get(0).candidate()).isEqualTo(categoryId);
        assertThat(chosen.get(0).affectedQueries()).containsExactlyInAnyOrder(Q1, Q2, Q4);
    }

    @Test
    void candidateThatNeverClearsThreshold_neverChosenEvenAlone() {
        CandidateIndex status = new CandidateIndex("products", List.of("status"));

        WorkloadEvaluationResult statusResult = new WorkloadEvaluationResult(
            status,
            List.of(new QueryEvaluation(Q4, evalResult(status, -6.8, "Seq Scan"))),
            -6.8
        );

        assertThat(WorkloadRecommendationEngine.chooseIndexSet(List.of(statusResult))).isEmpty();
    }

    @Test
    void nonOverlappingCandidates_bothChosenIndependently() {
        CandidateIndex ordersCustomerId = new CandidateIndex("orders", List.of("customer_id"));
        CandidateIndex paymentsOrderId = new CandidateIndex("payments", List.of("order_id"));

        WorkloadEvaluationResult a = new WorkloadEvaluationResult(
            ordersCustomerId,
            List.of(new QueryEvaluation("Q5", evalResult(ordersCustomerId, 91.4, "Bitmap Heap Scan"))),
            91.4
        );
        WorkloadEvaluationResult b = new WorkloadEvaluationResult(
            paymentsOrderId,
            List.of(new QueryEvaluation("Q8", evalResult(paymentsOrderId, 93.4, "Index Scan"))),
            93.4
        );

        List<Recommendation> chosen = WorkloadRecommendationEngine.chooseIndexSet(List.of(a, b));

        assertThat(chosen).hasSize(2);
    }

    @Test
    void emptyInput_emptyOutput() {
        assertThat(WorkloadRecommendationEngine.chooseIndexSet(List.of())).isEmpty();
    }

    @Test
    void unlimitedBudget_noArgOverload_and_explicitUnlimited_produceIdenticalResults() {
        CandidateIndex categoryId = new CandidateIndex("products", List.of("category_id"));
        CandidateIndex composite = new CandidateIndex("products", List.of("category_id", "status"));

        List<WorkloadEvaluationResult> results = List.of(
            new WorkloadEvaluationResult(categoryId, List.of(new QueryEvaluation(Q1, evalResult(categoryId, 92.0, "Bitmap Heap Scan"))), 92.0),
            new WorkloadEvaluationResult(composite, List.of(new QueryEvaluation(Q4, evalResult(composite, 96.2, "Index Scan"))), 96.2)
        );

        List<Recommendation> noArg = WorkloadRecommendationEngine.chooseIndexSet(results);
        List<Recommendation> explicitUnlimited = WorkloadRecommendationEngine.chooseIndexSet(results, IndexSetBudget.UNLIMITED);

        assertThat(noArg).isEqualTo(explicitUnlimited);
        assertThat(noArg).hasSize(2);
    }

    @Test
    void indexCountBudget_stopsAtMaxCount_evenThoughMoreCandidatesWouldClearTheBar() {
        CandidateIndex a = new CandidateIndex("orders", List.of("customer_id"));
        CandidateIndex b = new CandidateIndex("payments", List.of("order_id"));
        CandidateIndex c = new CandidateIndex("order_items", List.of("product_id"));

        List<WorkloadEvaluationResult> results = List.of(
            new WorkloadEvaluationResult(a, List.of(new QueryEvaluation("Qa", evalResult(a, 95.0, "Index Scan"))), 95.0),
            new WorkloadEvaluationResult(b, List.of(new QueryEvaluation("Qb", evalResult(b, 90.0, "Index Scan"))), 90.0),
            new WorkloadEvaluationResult(c, List.of(new QueryEvaluation("Qc", evalResult(c, 85.0, "Index Scan"))), 85.0)
        );

        List<Recommendation> chosen = WorkloadRecommendationEngine.chooseIndexSet(results, IndexSetBudget.indexCount(2));

        assertThat(chosen).hasSize(2);
        // Greedy picks best-marginal-score first — 95% then 90% — not just input order.
        assertThat(chosen.get(0).candidate()).isEqualTo(a);
        assertThat(chosen.get(1).candidate()).isEqualTo(b);
    }

    @Test
    void storageBudget_stopsBeforeExceedingIt() {
        CandidateIndex a = new CandidateIndex("orders", List.of("customer_id"));
        CandidateIndex b = new CandidateIndex("payments", List.of("order_id"));

        List<WorkloadEvaluationResult> results = List.of(
            new WorkloadEvaluationResult(a, List.of(new QueryEvaluation("Qa", evalResultWithSize(a, 95.0, 6000L))), 95.0),
            new WorkloadEvaluationResult(b, List.of(new QueryEvaluation("Qb", evalResultWithSize(b, 90.0, 6000L))), 90.0)
        );

        // Room for exactly one 6000-byte index, not both (12000 total).
        List<Recommendation> chosen = WorkloadRecommendationEngine.chooseIndexSet(results, IndexSetBudget.storageBytes(10_000L));

        assertThat(chosen).hasSize(1);
        assertThat(chosen.get(0).candidate()).isEqualTo(a); // higher marginal score, picked first
    }

    @Test
    void storageBudget_exactBoundaryFit_candidateIncludedNotExcluded() {
        CandidateIndex a = new CandidateIndex("orders", List.of("customer_id"));

        List<WorkloadEvaluationResult> results = List.of(
            new WorkloadEvaluationResult(a, List.of(new QueryEvaluation("Qa", evalResultWithSize(a, 95.0, 10_000L))), 95.0)
        );

        List<Recommendation> chosen = WorkloadRecommendationEngine.chooseIndexSet(results, IndexSetBudget.storageBytes(10_000L));

        assertThat(chosen).hasSize(1); // budget == exact size: fits, not an off-by-one exclusion
    }

    @Test
    void storageBudget_accumulatesAcrossMultipleChosenIndexes() {
        CandidateIndex a = new CandidateIndex("orders", List.of("customer_id"));
        CandidateIndex b = new CandidateIndex("payments", List.of("order_id"));
        CandidateIndex c = new CandidateIndex("order_items", List.of("product_id"));

        List<WorkloadEvaluationResult> results = List.of(
            new WorkloadEvaluationResult(a, List.of(new QueryEvaluation("Qa", evalResultWithSize(a, 95.0, 4000L))), 95.0),
            new WorkloadEvaluationResult(b, List.of(new QueryEvaluation("Qb", evalResultWithSize(b, 90.0, 4000L))), 90.0),
            new WorkloadEvaluationResult(c, List.of(new QueryEvaluation("Qc", evalResultWithSize(c, 85.0, 4000L))), 85.0)
        );

        // First two (4000 + 4000 = 8000) fit within 9000; the third would push to 12000, over budget.
        List<Recommendation> chosen = WorkloadRecommendationEngine.chooseIndexSet(results, IndexSetBudget.storageBytes(9_000L));

        assertThat(chosen).hasSize(2);
        assertThat(chosen).extracting(Recommendation::candidate).containsExactly(a, b);
    }
}
