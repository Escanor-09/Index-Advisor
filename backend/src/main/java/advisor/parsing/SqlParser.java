package advisor.parsing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

public class SqlParser {

    public static ParsedQuery parse(String sql) {
        Statement statement;

        try {
            statement = CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            throw new IllegalArgumentException("Failed to parse SQL: " + sql, e);
        }

        if (!(statement instanceof Select select)) {
            throw new IllegalArgumentException("Only SELECT statements are supported: " + sql);
        }

        PlainSelect plainSelect = select.getPlainSelect();

        if (plainSelect == null) {
            throw new IllegalArgumentException("Only plain SELECT statements are supported: " + sql);
        }

        FromItem fromItem = plainSelect.getFromItem();

        if (!(fromItem instanceof Table primaryTableObj)) {
            throw new IllegalArgumentException("Only single-table FROM clauses are supported: " + sql);
        }

        String primaryTable = primaryTableObj.getName();

        Map<String, String> aliasToTable = new HashMap<>();
        addToAliasMap(aliasToTable, primaryTableObj);

        List<JoinClause> joins = extractJoins(plainSelect.getJoins(), aliasToTable, primaryTable);

        List<TableColumn> qualifiedFilterColumns = new ArrayList<>();
        extractQualifiedColumns(plainSelect.getWhere(), aliasToTable, primaryTable, qualifiedFilterColumns);

        List<TableColumn> distinctFilterColumns = qualifiedFilterColumns.stream()
            .distinct()
            .sorted(Comparator.comparing(TableColumn::table).thenComparing(TableColumn::column))
            .toList();

        List<TableColumn> distinctOrderByColumns = extractOrderByColumns(plainSelect.getOrderByElements(), aliasToTable, primaryTable)
            .stream()
            .distinct()
            .sorted(Comparator.comparing(TableColumn::table).thenComparing(TableColumn::column))
            .toList();

        return new ParsedQuery(primaryTable, distinctFilterColumns, joins, distinctOrderByColumns);
    }

    /**
     * Only a plain column reference per ORDER BY item is recognized (e.g.
     * "ORDER BY orders.order_date"); an expression (e.g. "ORDER BY total * 2")
     * contributes nothing, mirroring extractQualifiedColumns' treatment of
     * non-column leaves — a wrong guess here would propose a sort-avoidance
     * index that can't actually satisfy the real sort.
     */
    private static List<TableColumn> extractOrderByColumns(
        List<OrderByElement> orderByElements,
        Map<String, String> aliasToTable,
        String defaultTable
    ) {
        List<TableColumn> columns = new ArrayList<>();

        if (orderByElements == null) {
            return columns;
        }

        for (OrderByElement element : orderByElements) {
            if (element.getExpression() instanceof Column column) {
                columns.add(new TableColumn(resolveTable(column, aliasToTable, defaultTable), column.getColumnName()));
            }
        }

        return columns;
    }

    /**
     * Only simple two-table equi-joins are recognized: a single
     * "ON left.col = right.col" condition. Multi-condition ON clauses,
     * non-equality joins, and joins against something other than a plain
     * table (e.g. a subquery) are a documented scope boundary — skipped
     * silently rather than guessed at or crashing on, since a wrong guess
     * here would propose a misleading candidate index.
     */
    private static List<JoinClause> extractJoins(
        List<Join> jsqlJoins,
        Map<String, String> aliasToTable,
        String primaryTable
    ) {
        List<JoinClause> joins = new ArrayList<>();

        if (jsqlJoins == null) {
            return joins;
        }

        for (Join join : jsqlJoins) {
            if (!(join.getRightItem() instanceof Table joinedTableObj)) {
                continue;
            }

            addToAliasMap(aliasToTable, joinedTableObj);

            Expression onExpression = join.getOnExpression();

            if (onExpression instanceof EqualsTo eq
                && eq.getLeftExpression() instanceof Column leftColumn
                && eq.getRightExpression() instanceof Column rightColumn) {

                String leftTable = resolveTable(leftColumn, aliasToTable, primaryTable);
                String rightTable = resolveTable(rightColumn, aliasToTable, primaryTable);

                joins.add(new JoinClause(leftTable, leftColumn.getColumnName(), rightTable, rightColumn.getColumnName()));
            }
        }

        return joins;
    }

    private static void addToAliasMap(Map<String, String> aliasToTable, Table table) {
        String realName = table.getName();
        aliasToTable.put(realName.toLowerCase(), realName);

        if (table.getAlias() != null) {
            aliasToTable.put(table.getAlias().getName().toLowerCase(), realName);
        }
    }

    private static String resolveTable(Column column, Map<String, String> aliasToTable, String defaultTable) {
        String qualifier = column.getTableName();

        if (qualifier == null) {
            return defaultTable;
        }

        return aliasToTable.getOrDefault(qualifier.toLowerCase(), qualifier);
    }

    /**
     * Walks the WHERE expression tree recursively. BinaryExpression covers
     * AND, OR, and every comparison operator (=, !=, >, >=, <, <=) uniformly,
     * so no per-operator special-casing is needed. A parenthesized group
     * (e.g. "(status = 'a' OR status = 'b')") parses as an ExpressionList
     * (JSqlParser 5.x replaced the old single-child Parenthesis node for
     * this case), so its elements are walked too.
     */
    private static void extractQualifiedColumns(
        Expression expression,
        Map<String, String> aliasToTable,
        String defaultTable,
        List<TableColumn> out
    ) {
        if (expression == null) {
            return;
        }

        if (expression instanceof Column column) {
            out.add(new TableColumn(resolveTable(column, aliasToTable, defaultTable), column.getColumnName()));
        } else if (expression instanceof BinaryExpression binary) {
            extractQualifiedColumns(binary.getLeftExpression(), aliasToTable, defaultTable, out);
            extractQualifiedColumns(binary.getRightExpression(), aliasToTable, defaultTable, out);
        } else if (expression instanceof ExpressionList<?> expressionList) {
            for (Expression inner : expressionList) {
                extractQualifiedColumns(inner, aliasToTable, defaultTable, out);
            }
        } else if (expression instanceof InExpression in) {
            extractQualifiedColumns(in.getLeftExpression(), aliasToTable, defaultTable, out);
        } else if (expression instanceof Between between) {
            extractQualifiedColumns(between.getLeftExpression(), aliasToTable, defaultTable, out);
        }
        // Literal leaves (LongValue, StringValue, etc.) contribute no columns.
    }
}
