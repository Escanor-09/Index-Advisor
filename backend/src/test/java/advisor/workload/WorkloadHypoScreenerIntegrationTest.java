package advisor.workload;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import advisor.WorkloadQueryAdvisor;
import advisor.candidates.CandidateIndex;
import advisor.database.PostgresManager;
import advisor.recommendation.Recommendation;

/**
 * Requires a real, running local PostgreSQL with the index_advisor database
 * and the hypopg extension enabled — same setup as
 * IndexEvaluatorIntegrationTest, plus `CREATE EXTENSION hypopg;`.
 * Deliberately not mocked: the whole point of this class is proving the
 * screen-then-validate pipeline behaves correctly against real HypoPG and
 * real Postgres statistics, not a simulation of them.
 */
@Tag("integration")
class WorkloadHypoScreenerIntegrationTest {

    private static PostgresManager db;

    @BeforeAll
    static void connect() {
        String user = System.getProperty("user.name");
        db = new PostgresManager("jdbc:postgresql://localhost:5432/index_advisor", user, "");
    }

    @AfterAll
    static void disconnect() {
        db.close();
    }

    @Test
    void screen_promotesHighSelectivityCandidate_dropsLowSelectivityOne() {
        // products.category_id: known real ~92-99% improvement throughout this project.
        // products.status: 3 heavily-skewed values, known real negative/no improvement —
        // documented as already cross-validated between HypoPG's estimate and the real
        // measurement, so HypoPG should screen it out here too.
        List<String> queries = List.of(
            "SELECT * FROM products WHERE category_id = 25;",
            "SELECT * FROM products WHERE status = 'active';"
        );

        WorkloadQueryAdvisor advisor = new WorkloadQueryAdvisor(db);
        WorkloadQueryAdvisor.WorkloadAnalysis analysis = advisor.analyzeDetailed(queries);

        assertThat(analysis.totalCandidates()).isEqualTo(2);
        // The whole point of screening: fewer candidates go on to pay the real CREATE/DROP INDEX cost.
        assertThat(analysis.screenedCandidates()).isLessThan(analysis.totalCandidates());

        boolean categoryIdPromoted = analysis.allResults().stream()
            .anyMatch(r -> r.candidate().equals(new CandidateIndex("products", List.of("category_id"))));

        assertThat(categoryIdPromoted).isTrue();
    }

    @Test
    void fullPipeline_stillProducesCorrectFinalRecommendation_withScreeningInFront() {
        List<String> queries = List.of("SELECT * FROM products WHERE category_id = 25;");

        WorkloadQueryAdvisor advisor = new WorkloadQueryAdvisor(db);
        List<Recommendation> recommendations = advisor.analyzeWorkload(queries);

        assertThat(recommendations).hasSize(1);
        assertThat(recommendations.get(0).candidate()).isEqualTo(new CandidateIndex("products", List.of("category_id")));
        assertThat(recommendations.get(0).estimatedImprovementPercent()).isGreaterThan(50.0);
    }

    @Test
    void screening_neverCreatesRealIndexes() {
        List<String> queries = List.of(
            "SELECT * FROM products WHERE category_id = 25;",
            "SELECT * FROM products WHERE status = 'active';",
            "SELECT * FROM orders WHERE customer_id = 500;"
        );

        new WorkloadQueryAdvisor(db).analyzeDetailed(queries);

        // Real evaluation (post-screening) uses idx_experiment_* names and cleans up after
        // itself already (see IndexEvaluatorIntegrationTest) — this confirms the screening
        // phase itself, which runs first and touches every candidate, adds nothing extra.
        List<String> leftover = db.executeColumn(
            "SELECT indexname FROM pg_indexes WHERE indexname LIKE 'idx_experiment%';"
        );

        assertThat(leftover).isEmpty();
    }
}
