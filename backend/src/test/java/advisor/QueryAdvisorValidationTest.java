package advisor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Pure logic, no database needed — QueryAdvisor.validateSql() is
 * package-private specifically so this can test it directly, the same
 * pattern IndexEvaluator.computeImprovement() already established.
 */
class QueryAdvisorValidationTest {

    @Test
    void nullQuery_rejected() {
        assertThatThrownBy(() -> QueryAdvisor.validateSql(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be empty");
    }

    @Test
    void blankQuery_rejected() {
        assertThatThrownBy(() -> QueryAdvisor.validateSql("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not be empty");
    }

    @Test
    void oversizedQuery_rejected() {
        String huge = "SELECT * FROM products WHERE id = 1".repeat(500); // well over MAX_SQL_LENGTH

        assertThatThrownBy(() -> QueryAdvisor.validateSql(huge))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exceeding the maximum");
    }

    @Test
    void queryAtExactLimit_accepted() {
        String atLimit = "-".repeat(ApiLimits.MAX_SQL_LENGTH);

        assertThatCode(() -> QueryAdvisor.validateSql(atLimit)).doesNotThrowAnyException();
    }

    @Test
    void ordinaryQuery_accepted() {
        assertThatCode(() -> QueryAdvisor.validateSql("SELECT * FROM products WHERE category_id = 25;"))
            .doesNotThrowAnyException();
    }
}
