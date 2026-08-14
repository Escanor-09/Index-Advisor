package advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import advisor.database.PostgresManager;
import advisor.recommendation.Recommendation;

/**
 * Requires a real, running local PostgreSQL with the index_advisor
 * database — same setup as IndexEvaluatorIntegrationTest. The point of
 * this class specifically: prove an oversized request is rejected before
 * WorkloadAdvisor's profiling step ever runs a real EXPLAIN against the
 * database, not just that validateWorkload() throws in isolation (already
 * covered by WorkloadQueryAdvisorValidationTest's pure unit tests).
 */
@Tag("integration")
class WorkloadQueryAdvisorIntegrationTest {

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
    void oversizedWorkload_rejectedFast_neverReachesRealProfiling() {
        List<String> oversized = new ArrayList<>();

        for (int i = 0; i < ApiLimits.MAX_WORKLOAD_QUERIES + 1; i++) {
            oversized.add("SELECT * FROM products WHERE id = " + i + ";");
        }

        WorkloadQueryAdvisor advisor = new WorkloadQueryAdvisor(db);

        long start = System.nanoTime();

        assertThatThrownBy(() -> advisor.analyzeDetailed(oversized))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exceeding the maximum");

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // Real profiling of 201 queries (one EXPLAIN ANALYZE each) would take at least
        // several hundred milliseconds; a validation-only rejection should be near-instant.
        // A generous bound, not a tight one — this is checking "no real DB work happened",
        // not benchmarking exact latency.
        assertThat(elapsedMs).isLessThan(200);
    }

    @Test
    void normalWorkload_stillWorksThroughTheRealPipeline() {
        WorkloadQueryAdvisor advisor = new WorkloadQueryAdvisor(db);

        List<Recommendation> recommendations = advisor.analyzeWorkload(
            List.of("SELECT * FROM products WHERE category_id = 25;")
        );

        assertThat(recommendations).isNotEmpty();
    }
}
