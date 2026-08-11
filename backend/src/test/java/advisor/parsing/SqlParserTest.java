package advisor.parsing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SqlParserTest {

    @Test
    void singleEqualityFilter() {
        ParsedQuery parsed = SqlParser.parse("SELECT * FROM categories WHERE id = 5;");

        assertThat(parsed.tableName()).isEqualTo("categories");
        assertThat(parsed.filterColumns()).containsExactly("id");
    }

    @Test
    void multipleAndedFilters() {
        ParsedQuery parsed = SqlParser.parse("SELECT * FROM products WHERE category_id = 5 AND price > 100;");

        assertThat(parsed.tableName()).isEqualTo("products");
        assertThat(parsed.filterColumns()).containsExactly("category_id", "price");
    }

    /**
     * Regression test for the real bug found during Milestone 5 development:
     * JSqlParser 5.x parses a parenthesized boolean group as
     * ParenthesedExpressionList, not the old Parenthesis node. The first
     * implementation only handled Parenthesis and silently dropped "status"
     * from the result.
     */
    @Test
    void parenthesizedOrInsideAnd_regressionForParenthesedExpressionListBug() {
        ParsedQuery parsed = SqlParser.parse(
            "SELECT * FROM orders WHERE (status = 'delivered' OR status = 'shipped') AND customer_id = 500;"
        );

        assertThat(parsed.tableName()).isEqualTo("orders");
        assertThat(parsed.filterColumns()).containsExactly("customer_id", "status");
    }

    @Test
    void noWhereClause_emptyFilterColumns() {
        ParsedQuery parsed = SqlParser.parse("SELECT * FROM products;");

        assertThat(parsed.tableName()).isEqualTo("products");
        assertThat(parsed.filterColumns()).isEmpty();
    }

    @Test
    void duplicateFilterColumn_deduplicated() {
        ParsedQuery parsed = SqlParser.parse(
            "SELECT * FROM products WHERE category_id = 5 OR category_id = 10;"
        );

        assertThat(parsed.filterColumns()).containsExactly("category_id");
    }

    @Test
    void nonSelectStatement_throws() {
        assertThatThrownBy(() -> SqlParser.parse("DROP TABLE customers;"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Only SELECT statements are supported");
    }

    @Test
    void simpleJoin_parsesJoinClauseWithAliasesResolved() {
        ParsedQuery parsed = SqlParser.parse(
            "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id WHERE c.status = 'active';"
        );

        assertThat(parsed.tableName()).isEqualTo("orders");
        assertThat(parsed.joins()).containsExactly(
            new JoinClause("orders", "customer_id", "customers", "id")
        );
    }

    @Test
    void joinQuery_filterOnJoinedTable_attributedToRealTableNotAlias() {
        ParsedQuery parsed = SqlParser.parse(
            "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id WHERE c.status = 'active';"
        );

        assertThat(parsed.qualifiedFilterColumns()).containsExactly(new TableColumn("customers", "status"));
        // Backward-compat filterColumns() only reflects the primary table — status belongs to customers, not orders.
        assertThat(parsed.filterColumns()).isEmpty();
    }

    @Test
    void joinQuery_filterOnPrimaryTableUnqualified_stillAttributedCorrectly() {
        ParsedQuery parsed = SqlParser.parse(
            "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id WHERE total_amount > 100;"
        );

        assertThat(parsed.qualifiedFilterColumns()).containsExactly(new TableColumn("orders", "total_amount"));
        assertThat(parsed.filterColumns()).containsExactly("total_amount");
    }

    @Test
    void noJoin_emptyJoinsList() {
        ParsedQuery parsed = SqlParser.parse("SELECT * FROM products WHERE category_id = 5;");

        assertThat(parsed.joins()).isEmpty();
    }
}
