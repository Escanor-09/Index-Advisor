package advisor.parsing;

/** A column reference resolved to its real table (alias already resolved away). */
public record TableColumn(String table, String column) {

    @Override
    public String toString() {
        return table + "." + column;
    }
}
