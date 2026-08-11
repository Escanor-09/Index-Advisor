package advisor.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import advisor.candidates.CandidateIndex;
import advisor.database.PostgresManager;

/**
 * Requires a real, running local PostgreSQL with the index_advisor
 * database (see docs/CURRENT_PROGRESS.md for setup) — deliberately not
 * mocked, matching this project's whole ethos of measuring against the
 * real thing rather than assuming behavior. Connects with the current OS
 * user and no password, same as Main.java's CLI defaults.
 */
@Tag("integration")
class IndexEvaluatorIntegrationTest {

    private static PostgresManager sandboxDb;
    private static PostgresManager wrongDb;

    @BeforeAll
    static void connect() {
        String user = System.getProperty("user.name");
        sandboxDb = new PostgresManager("jdbc:postgresql://localhost:5432/index_advisor", user, "");
        wrongDb = new PostgresManager("jdbc:postgresql://localhost:5432/postgres", user, "");
    }

    @AfterAll
    static void disconnect() {
        sandboxDb.close();
        wrongDb.close();
    }

    @Test
    void refusesToRunAgainstNonSandboxDatabase() {
        assertThatThrownBy(() -> new IndexEvaluator(wrongDb))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("index_advisor");
    }

    @Test
    void rejectsColumnThatDoesNotExistInTheRealSchema() {
        IndexEvaluator evaluator = new IndexEvaluator(sandboxDb);
        CandidateIndex bogus = new CandidateIndex("products", List.of("not_a_real_column"));

        assertThatThrownBy(() -> evaluator.evaluate(bogus, "SELECT * FROM products WHERE category_id = 1;"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown column");
    }

    @Test
    void measuresRealImprovementAndLeavesNoIndexBehind() {
        IndexEvaluator evaluator = new IndexEvaluator(sandboxDb);
        CandidateIndex candidate = new CandidateIndex("products", List.of("category_id"));

        EvaluationResult result = evaluator.evaluate(candidate, "SELECT * FROM products WHERE category_id = 25;");

        // Real, measured values against the actual 10,000-row products table — this is the
        // same candidate/query combination verified manually many times across this project.
        assertThat(result.improvementPercent()).isGreaterThan(50.0);
        assertThat(result.afterNodeType()).isNotEqualTo("Seq Scan");
        assertThat(result.indexSizeBytes()).isGreaterThan(0);

        List<String> leftoverIndexes = sandboxDb.executeColumn(
            "SELECT indexname FROM pg_indexes WHERE indexname LIKE 'idx_experiment%';"
        );
        assertThat(leftoverIndexes).isEmpty();
    }
}
