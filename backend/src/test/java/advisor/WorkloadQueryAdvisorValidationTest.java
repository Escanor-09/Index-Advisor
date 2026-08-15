package advisor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import advisor.candidates.CandidateIndex;

/**
 * Pure logic, no database needed — both validateWorkload() and
 * validateCandidateCount() are package-private specifically so this can
 * test them directly, the same pattern IndexEvaluator.computeImprovement()
 * already established.
 */
class WorkloadQueryAdvisorValidationTest {

    @Test
    void nullWorkload_rejected() {
        assertThatThrownBy(() -> WorkloadQueryAdvisor.validateWorkload(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one query");
    }

    @Test
    void emptyWorkload_rejected() {
        assertThatThrownBy(() -> WorkloadQueryAdvisor.validateWorkload(List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one query");
    }

    @Test
    void tooManyQueries_rejected() {
        List<String> oversized = new ArrayList<>();

        for (int i = 0; i < ApiLimits.MAX_WORKLOAD_QUERIES + 1; i++) {
            oversized.add("SELECT * FROM products WHERE id = " + i + ";");
        }

        assertThatThrownBy(() -> WorkloadQueryAdvisor.validateWorkload(oversized))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exceeding the maximum");
    }

    @Test
    void queryCountAtExactLimit_accepted() {
        List<String> atLimit = new ArrayList<>();

        for (int i = 0; i < ApiLimits.MAX_WORKLOAD_QUERIES; i++) {
            atLimit.add("SELECT * FROM products WHERE id = " + i + ";");
        }

        assertThatCode(() -> WorkloadQueryAdvisor.validateWorkload(atLimit)).doesNotThrowAnyException();
    }

    @Test
    void oneOversizedQueryAmongValidOnes_wholeWorkloadRejected() {
        List<String> workload = List.of(
            "SELECT * FROM products WHERE id = 1;",
            "SELECT * FROM orders WHERE id = 1".repeat(500) + ";"
        );

        assertThatThrownBy(() -> WorkloadQueryAdvisor.validateWorkload(workload))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exceeding the maximum");
    }

    @Test
    void ordinaryWorkload_accepted() {
        List<String> workload = List.of(
            "SELECT * FROM products WHERE category_id = 25;",
            "SELECT * FROM orders WHERE status = 'pending';"
        );

        assertThatCode(() -> WorkloadQueryAdvisor.validateWorkload(workload)).doesNotThrowAnyException();
    }

    @Test
    void tooManyCandidates_rejected() {
        List<CandidateIndex> oversized = new ArrayList<>();

        for (int i = 0; i < ApiLimits.MAX_CANDIDATES + 1; i++) {
            oversized.add(new CandidateIndex("table" + i, List.of("column" + i)));
        }

        assertThatThrownBy(() -> WorkloadQueryAdvisor.validateCandidateCount(oversized))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("distinct index candidates");
    }

    @Test
    void candidateCountAtExactLimit_accepted() {
        List<CandidateIndex> atLimit = new ArrayList<>();

        for (int i = 0; i < ApiLimits.MAX_CANDIDATES; i++) {
            atLimit.add(new CandidateIndex("table" + i, List.of("column" + i)));
        }

        assertThatCode(() -> WorkloadQueryAdvisor.validateCandidateCount(atLimit)).doesNotThrowAnyException();
    }

    @Test
    void ordinaryCandidateList_accepted() {
        List<CandidateIndex> candidates = List.of(
            new CandidateIndex("products", List.of("category_id")),
            new CandidateIndex("orders", List.of("customer_id"))
        );

        assertThatCode(() -> WorkloadQueryAdvisor.validateCandidateCount(candidates)).doesNotThrowAnyException();
    }
}
