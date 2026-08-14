package advisor.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import advisor.candidates.CandidateIndex;
import advisor.evaluation.EvaluationResult;

class RecommendationEngineTest {

    private static final String SQL = "SELECT * FROM products WHERE category_id = 25 AND status = 'active';";

    private static EvaluationResult result(String table, List<String> columns, double improvementPercent) {
        String afterNodeType = improvementPercent > 0 ? "Bitmap Heap Scan" : "Seq Scan";

        return new EvaluationResult(
            new CandidateIndex(table, columns),
            "Seq Scan", afterNodeType,
            "Seq Scan -> " + afterNodeType,
            400.0, 10.0,
            1.0, 0.1,
            improvementPercent,
            8192L
        );
    }

    @Test
    void belowMinimumThreshold_filteredOutEntirely() {
        // Real value observed for products(status): -6.8% — not just ranked last, excluded.
        List<EvaluationResult> results = List.of(result("products", List.of("status"), -6.8));

        assertThat(RecommendationEngine.recommend(results, SQL)).isEmpty();
    }

    @Test
    void aboveMinimumThreshold_recommended() {
        List<EvaluationResult> results = List.of(result("products", List.of("category_id"), 92.0));

        List<Recommendation> recommendations = RecommendationEngine.recommend(results, SQL);

        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.get(0).impact()).isEqualTo("HIGH");
        assertThat(recommendations.get(0).affectedQueries()).containsExactly(SQL);
    }

    @Test
    void ranksByImprovementDescending_notInputOrder() {
        List<EvaluationResult> results = List.of(
            result("products", List.of("category_id"), 92.0),
            result("products", List.of("category_id", "status"), 96.2)
        );

        List<Recommendation> recommendations = RecommendationEngine.recommend(results, SQL);

        assertThat(recommendations).hasSize(2);
        assertThat(recommendations.get(0).candidate().columns()).containsExactly("category_id", "status");
        assertThat(recommendations.get(1).candidate().columns()).containsExactly("category_id");
    }

    @Test
    void impactTiers_matchDocumentedThresholds() {
        assertThat(tierFor(55.0)).isEqualTo("HIGH");   // >= 50
        assertThat(tierFor(50.0)).isEqualTo("HIGH");   // boundary, inclusive
        assertThat(tierFor(25.0)).isEqualTo("MEDIUM"); // >= 20
        assertThat(tierFor(20.0)).isEqualTo("MEDIUM"); // boundary, inclusive
        assertThat(tierFor(15.0)).isEqualTo("LOW");    // >= 10 (recommend threshold) but < 20
    }

    private static String tierFor(double improvementPercent) {
        List<EvaluationResult> results = List.of(result("t", List.of("c"), improvementPercent));
        return RecommendationEngine.recommend(results, SQL).get(0).impact();
    }

    @Test
    void compositeNoBetterThanItsPrefix_prefixWins_compositeDropped() {
        // (category_id) alone already gets 92%; the wider (category_id, status)
        // composite gets no more than that — pure extra cost, should be pruned.
        List<EvaluationResult> results = List.of(
            result("products", List.of("category_id"), 92.0),
            result("products", List.of("category_id", "status"), 92.0)
        );

        List<Recommendation> recommendations = RecommendationEngine.recommend(results, SQL);

        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.get(0).candidate().columns()).containsExactly("category_id");
    }

    @Test
    void compositeBeatsItsPrefix_bothSurvive() {
        List<EvaluationResult> results = List.of(
            result("products", List.of("category_id"), 92.0),
            result("products", List.of("category_id", "status"), 96.2)
        );

        List<Recommendation> recommendations = RecommendationEngine.recommend(results, SQL);

        assertThat(recommendations).hasSize(2);
    }

    @Test
    void sameColumnsDifferentTables_noCrossTableDomination() {
        List<EvaluationResult> results = List.of(
            result("products", List.of("id"), 92.0),
            result("orders", List.of("id"), 40.0)
        );

        List<Recommendation> recommendations = RecommendationEngine.recommend(results, SQL);

        assertThat(recommendations).hasSize(2);
    }
}
