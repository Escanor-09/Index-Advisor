package advisor.workload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Milestone 7: turn a queries.sql file into individual SQL statements.
 * Deliberately does nothing beyond that yet — parsing (SqlParser) and
 * plan collection (QueryPlan) stay separate, profiling is Milestone 8.
 */
public class WorkloadReader {

    public static List<String> readQueries(Path path) {
        String content;

        try {
            content = Files.readString(path);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read workload file: " + path, e);
        }

        String withoutComments = content.lines()
                .filter(line -> !line.strip().startsWith("--"))
                .reduce("", (a, b) -> a + "\n" + b);

        List<String> queries = new ArrayList<>();

        for (String statement : withoutComments.split(";")) {
            String trimmed = statement.strip();

            if (!trimmed.isEmpty()) {
                queries.add(trimmed + ";");
            }
        }

        return queries;
    }
}
