package advisor.candidates;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import advisor.parsing.JoinClause;
import advisor.parsing.ParsedQuery;
import advisor.parsing.TableColumn;

class CandidateGeneratorTest {

    @Test
    void singleFilterColumn_producesOneCandidate() {
        ParsedQuery query = ParsedQuery.singleTable("products", List.of("category_id"));

        List<CandidateIndex> candidates = CandidateGenerator.generate(query);

        assertThat(candidates).containsExactly(
            new CandidateIndex("products", List.of("category_id"))
        );
    }

    @Test
    void twoFilterColumns_producesTwoSingleColumnCandidatesPlusOneComposite() {
        ParsedQuery query = ParsedQuery.singleTable("products", List.of("category_id", "status"));

        List<CandidateIndex> candidates = CandidateGenerator.generate(query);

        assertThat(candidates).containsExactly(
            new CandidateIndex("products", List.of("category_id")),
            new CandidateIndex("products", List.of("status")),
            new CandidateIndex("products", List.of("category_id", "status"))
        );
    }

    @Test
    void noFilterColumns_noCandidates() {
        ParsedQuery query = ParsedQuery.singleTable("products", List.of());

        assertThat(CandidateGenerator.generate(query)).isEmpty();
    }

    @Test
    void generateForWorkload_deduplicatesIdenticalCandidatesAcrossQueries() {
        List<ParsedQuery> queries = List.of(
            ParsedQuery.singleTable("products", List.of("category_id")),
            ParsedQuery.singleTable("products", List.of("category_id")), // same candidate, different query
            ParsedQuery.singleTable("orders", List.of("customer_id"))
        );

        List<CandidateIndex> candidates = CandidateGenerator.generateForWorkload(queries);

        assertThat(candidates).containsExactly(
            new CandidateIndex("products", List.of("category_id")),
            new CandidateIndex("orders", List.of("customer_id"))
        );
    }

    @Test
    void joinClause_producesOneCandidatePerSide() {
        ParsedQuery query = new ParsedQuery(
            "orders",
            List.of(),
            List.of(new JoinClause("orders", "customer_id", "customers", "id")),
            List.of()
        );

        List<CandidateIndex> candidates = CandidateGenerator.generate(query);

        assertThat(candidates).containsExactly(
            new CandidateIndex("orders", List.of("customer_id")),
            new CandidateIndex("customers", List.of("id"))
        );
    }

    @Test
    void joinPlusFilterOnJoinedTable_producesCandidatesForBothTables() {
        // SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id WHERE c.status = 'active';
        ParsedQuery query = new ParsedQuery(
            "orders",
            List.of(new TableColumn("customers", "status")),
            List.of(new JoinClause("orders", "customer_id", "customers", "id")),
            List.of()
        );

        List<CandidateIndex> candidates = CandidateGenerator.generate(query);

        assertThat(candidates).containsExactlyInAnyOrder(
            new CandidateIndex("customers", List.of("status")), // filter candidate on the joined table
            new CandidateIndex("orders", List.of("customer_id")), // join candidate, left side
            new CandidateIndex("customers", List.of("id")) // join candidate, right side
        );
    }

    @Test
    void joinColumnAlsoFiltered_deduplicatedNotDoubled() {
        // The join column and the WHERE filter column happen to be the same — should appear once.
        ParsedQuery query = new ParsedQuery(
            "orders",
            List.of(new TableColumn("orders", "customer_id")),
            List.of(new JoinClause("orders", "customer_id", "customers", "id")),
            List.of()
        );

        List<CandidateIndex> candidates = CandidateGenerator.generate(query);

        assertThat(candidates).containsExactly(
            new CandidateIndex("orders", List.of("customer_id")),
            new CandidateIndex("customers", List.of("id"))
        );
    }

    @Test
    void orderByOnlyColumn_producesSingleColumnSortCandidate() {
        // SELECT * FROM orders ORDER BY order_date; -- no WHERE clause at all
        ParsedQuery query = new ParsedQuery("orders", List.of(), List.of(), List.of(new TableColumn("orders", "order_date")));

        List<CandidateIndex> candidates = CandidateGenerator.generate(query);

        assertThat(candidates).containsExactly(
            new CandidateIndex("orders", List.of("order_date"))
        );
    }

    @Test
    void filterPlusOrderByOnDifferentColumns_producesCompositeAndSingleCandidates() {
        // SELECT * FROM orders WHERE customer_id = 5 ORDER BY order_date;
        ParsedQuery query = new ParsedQuery(
            "orders",
            List.of(new TableColumn("orders", "customer_id")),
            List.of(),
            List.of(new TableColumn("orders", "order_date"))
        );

        List<CandidateIndex> candidates = CandidateGenerator.generate(query);

        assertThat(candidates).containsExactly(
            new CandidateIndex("orders", List.of("customer_id")),
            new CandidateIndex("orders", List.of("order_date")),
            new CandidateIndex("orders", List.of("customer_id", "order_date"))
        );
    }

    @Test
    void orderByColumnAlsoFiltered_noRedundantSingleColumnCandidate() {
        // SELECT * FROM orders WHERE customer_id = 5 ORDER BY customer_id;
        ParsedQuery query = new ParsedQuery(
            "orders",
            List.of(new TableColumn("orders", "customer_id")),
            List.of(),
            List.of(new TableColumn("orders", "customer_id"))
        );

        List<CandidateIndex> candidates = CandidateGenerator.generate(query);

        // Only the filter-column candidate — no duplicate single-column order-by
        // candidate, and no self-composite (customer_id, customer_id).
        assertThat(candidates).containsExactly(
            new CandidateIndex("orders", List.of("customer_id"))
        );
    }
}
