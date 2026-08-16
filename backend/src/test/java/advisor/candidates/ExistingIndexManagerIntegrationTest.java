package advisor.candidates;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import advisor.database.PostgresManager;

/**
 * Requires a real, running local PostgreSQL with the ecom_test database
 * (see IndexEvaluatorIntegrationTest for the same setup). Creates one real,
 * temporary index to verify pg_indexes is read and parsed correctly, and
 * always drops it — same "leave no index behind" discipline as the rest of
 * this project's integration tests.
 */
@Tag("integration")
class ExistingIndexManagerIntegrationTest {

    private static final String TEMP_INDEX_NAME = "idx_test_existing_index_manager";

    private static PostgresManager db;
    private static ExistingIndexManager manager;

    @BeforeAll
    static void connectAndCreateTempIndex() {
        String user = System.getProperty("user.name");
        db = new PostgresManager("jdbc:postgresql://localhost:5432/ecom_test", user, "");
        manager = new ExistingIndexManager(db);

        db.executeUpdate("DROP INDEX IF EXISTS " + TEMP_INDEX_NAME + ";");
        db.executeUpdate("CREATE INDEX " + TEMP_INDEX_NAME + " ON products (category_id, status);");
    }

    @AfterAll
    static void dropTempIndexAndDisconnect() {
        db.executeUpdate("DROP INDEX IF EXISTS " + TEMP_INDEX_NAME + ";");
        db.close();
    }

    @Test
    void getExistingIndexes_findsRealCompositeIndexWithColumnsInOrder() {
        List<ExistingIndex> indexes = manager.getExistingIndexes();

        assertThat(indexes).anySatisfy(idx -> {
            assertThat(idx.tableName()).isEqualTo("products");
            assertThat(idx.columns()).containsExactly("category_id", "status");
        });
    }

    @Test
    void isCoveredByExistingIndex_leadingPrefixColumn_true() {
        List<ExistingIndex> indexes = manager.getExistingIndexes();
        CandidateIndex candidate = new CandidateIndex("products", List.of("category_id"));

        assertThat(manager.isCoveredByExistingIndex(candidate, indexes)).isTrue();
    }

    @Test
    void isCoveredByExistingIndex_exactCompositeMatch_true() {
        List<ExistingIndex> indexes = manager.getExistingIndexes();
        CandidateIndex candidate = new CandidateIndex("products", List.of("category_id", "status"));

        assertThat(manager.isCoveredByExistingIndex(candidate, indexes)).isTrue();
    }

    @Test
    void isCoveredByExistingIndex_trailingColumnAlone_false() {
        // "status" is not a leading prefix of (category_id, status) — Postgres can't
        // use this composite index to serve a query that only filters on status.
        List<ExistingIndex> indexes = manager.getExistingIndexes();
        CandidateIndex candidate = new CandidateIndex("products", List.of("status"));

        assertThat(manager.isCoveredByExistingIndex(candidate, indexes)).isFalse();
    }

    @Test
    void isCoveredByExistingIndex_differentTable_false() {
        List<ExistingIndex> indexes = manager.getExistingIndexes();
        CandidateIndex candidate = new CandidateIndex("orders", List.of("category_id"));

        assertThat(manager.isCoveredByExistingIndex(candidate, indexes)).isFalse();
    }
}
