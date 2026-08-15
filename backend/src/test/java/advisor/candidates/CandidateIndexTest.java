package advisor.candidates;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import advisor.parsing.JoinClause;
import advisor.parsing.ParsedQuery;
import advisor.parsing.TableColumn;

class CandidateIndexTest {

    @Test
    void appliesTo_sameTableAndSubsetOfColumns_true() {
        CandidateIndex candidate = new CandidateIndex("products", List.of("category_id"));
        ParsedQuery query = ParsedQuery.singleTable("products", List.of("category_id", "status"));

        assertThat(candidate.appliesTo(query)).isTrue();
    }

    @Test
    void appliesTo_differentTable_false() {
        CandidateIndex candidate = new CandidateIndex("orders", List.of("customer_id"));
        ParsedQuery query = ParsedQuery.singleTable("products", List.of("customer_id"));

        assertThat(candidate.appliesTo(query)).isFalse();
    }

    @Test
    void appliesTo_columnNotFilteredByQuery_false() {
        CandidateIndex candidate = new CandidateIndex("products", List.of("price"));
        ParsedQuery query = ParsedQuery.singleTable("products", List.of("category_id"));

        assertThat(candidate.appliesTo(query)).isFalse();
    }

    @Test
    void appliesTo_compositeCandidateRequiresAllColumnsPresent() {
        CandidateIndex composite = new CandidateIndex("products", List.of("category_id", "status"));

        assertThat(composite.appliesTo(ParsedQuery.singleTable("products", List.of("category_id")))).isFalse();
        assertThat(composite.appliesTo(ParsedQuery.singleTable("products", List.of("category_id", "status")))).isTrue();
    }

    @Test
    void toString_formatsAsTableAndColumnList() {
        CandidateIndex composite = new CandidateIndex("products", List.of("category_id", "status"));

        assertThat(composite.toString()).isEqualTo("products(category_id, status)");
    }

    @Test
    void appliesTo_joinColumnOnLeftSide_true() {
        CandidateIndex candidate = new CandidateIndex("orders", List.of("customer_id"));
        ParsedQuery query = new ParsedQuery(
            "orders",
            List.of(),
            List.of(new JoinClause("orders", "customer_id", "customers", "id")),
            List.of()
        );

        assertThat(candidate.appliesTo(query)).isTrue();
    }

    @Test
    void appliesTo_joinColumnOnRightSide_true() {
        CandidateIndex candidate = new CandidateIndex("customers", List.of("id"));
        ParsedQuery query = new ParsedQuery(
            "orders",
            List.of(),
            List.of(new JoinClause("orders", "customer_id", "customers", "id")),
            List.of()
        );

        assertThat(candidate.appliesTo(query)).isTrue();
    }

    @Test
    void appliesTo_compositeCandidateNeverMatchesJoinColumn() {
        // Join clauses are always single-column per side; a composite candidate
        // should never match via the join path, only via the filter-column path.
        CandidateIndex composite = new CandidateIndex("orders", List.of("customer_id", "status"));
        ParsedQuery query = new ParsedQuery(
            "orders",
            List.of(),
            List.of(new JoinClause("orders", "customer_id", "customers", "id")),
            List.of()
        );

        assertThat(composite.appliesTo(query)).isFalse();
    }

    @Test
    void appliesTo_orderByOnlyColumn_true() {
        // Regression: a pure sort-avoidance candidate (no matching WHERE filter)
        // must still apply to the query that motivated it, otherwise
        // WorkloadIndexEvaluator would silently never evaluate it.
        CandidateIndex candidate = new CandidateIndex("orders", List.of("order_date"));
        ParsedQuery query = new ParsedQuery("orders", List.of(), List.of(), List.of(new TableColumn("orders", "order_date")));

        assertThat(candidate.appliesTo(query)).isTrue();
    }

    @Test
    void appliesTo_filterPlusOrderByComposite_true() {
        CandidateIndex composite = new CandidateIndex("orders", List.of("customer_id", "order_date"));
        ParsedQuery query = new ParsedQuery(
            "orders",
            List.of(new TableColumn("orders", "customer_id")),
            List.of(),
            List.of(new TableColumn("orders", "order_date"))
        );

        assertThat(composite.appliesTo(query)).isTrue();
    }
}
