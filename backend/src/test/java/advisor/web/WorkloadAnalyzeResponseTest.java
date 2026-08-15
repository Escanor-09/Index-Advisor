package advisor.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import advisor.WorkloadQueryAdvisor;
import advisor.candidates.CandidateIndex;
import advisor.evaluation.EvaluationResult;
import advisor.parsing.ParsedQuery;
import advisor.recommendation.Recommendation;
import advisor.workload.QueryEvaluation;
import advisor.workload.WorkloadEvaluationResult;
import advisor.workload.WorkloadProfile;
import advisor.workload.WorkloadQuery;

class WorkloadAnalyzeResponseTest {

    private static final String Q1 = "SELECT * FROM orders WHERE customer_id = 500;";
    private static final String Q2 = "SELECT * FROM orders WHERE customer_id = 600;";
    private static final String Q3 = "SELECT * FROM orders WHERE status = 'pending';";
    private static final String Q4 = "SELECT * FROM payments WHERE order_id = 100;";

    private static EvaluationResult evaluation(CandidateIndex candidate, double before, double after, double improvement) {
        return new EvaluationResult(candidate, "Seq Scan", "Index Scan", "Seq Scan -> Index Scan", 400.0, 10.0, before, after, improvement, 8192L);
    }

    private static WorkloadQuery dummyQuery(String sql) {
        return new WorkloadQuery(sql, ParsedQuery.singleTable("orders", List.of()), null);
    }

    private static WorkloadProfile profileOf(String... queries) {
        return new WorkloadProfile(
            List.of(queries).stream().map(WorkloadAnalyzeResponseTest::dummyQuery).toList(),
            Map.of()
        );
    }

    // totalCandidates/screenedCandidates don't affect anything WorkloadAnalyzeResponse computes
    // (they're diagnostic-only fields) — arbitrary but plausible values are fine here.
    private static WorkloadQueryAdvisor.WorkloadAnalysis analysisOf(
        WorkloadProfile profile, List<WorkloadEvaluationResult> allResults, List<Recommendation> recommendations
    ) {
        return new WorkloadQueryAdvisor.WorkloadAnalysis(profile, allResults, recommendations, allResults.size(), allResults.size());
    }

    @Test
    void costAndImprovement_onlyReflectHelpedQueries_notEverythingEvaluated() {
        CandidateIndex candidate = new CandidateIndex("orders", List.of("customer_id"));

        // Evaluated against 2 queries, but only Q1 cleared the recommend threshold — Q2's
        // barely-positive result must not dilute the reported cost/improvement.
        WorkloadEvaluationResult raw = new WorkloadEvaluationResult(
            candidate,
            List.of(
                new QueryEvaluation(Q1, evaluation(candidate, 10.0, 1.0, 90.0)),
                new QueryEvaluation(Q2, evaluation(candidate, 10.0, 9.5, 5.0))
            ),
            95.0
        );

        Recommendation recommendation = new Recommendation(
            candidate, "HIGH", List.of(Q1), "reason", "Seq Scan -> Index Scan", 90.0, 8192L, List.of()
        );

        WorkloadQueryAdvisor.WorkloadAnalysis analysis = analysisOf(
            profileOf(Q1, Q2, Q3, Q4), List.of(raw), List.of(recommendation)
        );

        WorkloadAnalyzeResponse response = WorkloadAnalyzeResponse.from(analysis);

        assertThat(response.recommendedIndexes()).hasSize(1);
        WorkloadAnalyzeResponse.IndexView view = response.recommendedIndexes().get(0);

        assertThat(view.index()).isEqualTo("orders(customer_id)");
        assertThat(view.cost()).isEqualTo(1.0);
        assertThat(view.improvement()).isEqualTo(90.0);
        assertThat(view.coverage()).isEqualTo(25.0); // 1 of 4 workload queries
    }

    @Test
    void coverage_reflectsFractionOfWholeWorkload_notFractionOfCandidatesApplicableQueries() {
        CandidateIndex candidate = new CandidateIndex("orders", List.of("status"));

        WorkloadEvaluationResult raw = new WorkloadEvaluationResult(
            candidate,
            List.of(
                new QueryEvaluation(Q1, evaluation(candidate, 10.0, 2.0, 80.0)),
                new QueryEvaluation(Q3, evaluation(candidate, 10.0, 2.0, 80.0))
            ),
            160.0
        );

        Recommendation recommendation = new Recommendation(
            candidate, "HIGH", List.of(Q1, Q3), "reason", "Seq Scan -> Index Scan", 80.0, 4096L, List.of()
        );

        WorkloadQueryAdvisor.WorkloadAnalysis analysis = analysisOf(
            profileOf(Q1, Q2, Q3, Q4), List.of(raw), List.of(recommendation)
        );

        WorkloadAnalyzeResponse.IndexView view = WorkloadAnalyzeResponse.from(analysis).recommendedIndexes().get(0);

        assertThat(view.coverage()).isEqualTo(50.0); // 2 of 4 workload queries
    }

    @Test
    void overallBeforeAfterImprovement_averagedAcrossChosenIndexes() {
        CandidateIndex a = new CandidateIndex("orders", List.of("customer_id"));
        CandidateIndex b = new CandidateIndex("payments", List.of("order_id"));

        WorkloadEvaluationResult rawA = new WorkloadEvaluationResult(
            a, List.of(new QueryEvaluation(Q1, evaluation(a, 20.0, 2.0, 90.0))), 90.0
        );
        WorkloadEvaluationResult rawB = new WorkloadEvaluationResult(
            b, List.of(new QueryEvaluation(Q4, evaluation(b, 10.0, 4.0, 60.0))), 60.0
        );

        Recommendation recA = new Recommendation(a, "HIGH", List.of(Q1), "r", "Seq Scan -> Index Scan", 90.0, 1024L, List.of());
        Recommendation recB = new Recommendation(b, "MEDIUM", List.of(Q4), "r", "Seq Scan -> Index Scan", 60.0, 2048L, List.of());

        WorkloadQueryAdvisor.WorkloadAnalysis analysis = analysisOf(
            profileOf(Q1, Q2, Q3, Q4), List.of(rawA, rawB), List.of(recA, recB)
        );

        WorkloadAnalyzeResponse response = WorkloadAnalyzeResponse.from(analysis);

        // (20.0 + 10.0) / 2 = 15.0 before, (2.0 + 4.0) / 2 = 3.0 after
        assertThat(response.beforeCost()).isEqualTo(15.0);
        assertThat(response.afterCost()).isEqualTo(3.0);
        assertThat(response.improvement()).isCloseTo(80.0, within(0.01)); // 100 * (15-3)/15
    }

    @Test
    void noRecommendations_emptyResponseNotAnError() {
        WorkloadQueryAdvisor.WorkloadAnalysis analysis = analysisOf(
            profileOf(Q1, Q2), List.of(), List.of()
        );

        WorkloadAnalyzeResponse response = WorkloadAnalyzeResponse.from(analysis);

        assertThat(response.recommendedIndexes()).isEmpty();
        assertThat(response.beforeCost()).isEqualTo(0.0);
        assertThat(response.afterCost()).isEqualTo(0.0);
        assertThat(response.improvement()).isEqualTo(0.0);
    }

    @Test
    void recommendationWithNoMatchingRawResult_skippedNotThrown() {
        CandidateIndex candidate = new CandidateIndex("orders", List.of("customer_id"));
        Recommendation orphan = new Recommendation(
            candidate, "HIGH", List.of(Q1), "reason", "Seq Scan -> Index Scan", 90.0, 8192L, List.of()
        );

        // allResults deliberately doesn't contain a WorkloadEvaluationResult for this candidate.
        WorkloadQueryAdvisor.WorkloadAnalysis analysis = analysisOf(
            profileOf(Q1, Q2), List.of(), List.of(orphan)
        );

        assertThat(WorkloadAnalyzeResponse.from(analysis).recommendedIndexes()).isEmpty();
    }
}
