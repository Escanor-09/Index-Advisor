package advisor.candidates;

import java.util.List;

/** A real index that already exists in the target schema, discovered via pg_indexes. */
public record ExistingIndex(String tableName, List<String> columns) {}
