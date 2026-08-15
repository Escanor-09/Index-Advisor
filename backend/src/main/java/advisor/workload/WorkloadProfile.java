package advisor.workload;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

public record WorkloadProfile(
    List<WorkloadQuery> queries,
    Map<String, Integer> columnFrequency // "table.column" -> number of queries filtering on it
) {

    public String report() {
        StringBuilder sb = new StringBuilder();

        sb.append("Queries analyzed: ").append(queries.size()).append("\n\n");

        columnFrequency.entrySet().stream()
            .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed())
            .forEach(entry -> sb.append(String.format(
                "  %-30s frequency=%d  usage=WHERE equality predicate%n",
                entry.getKey(), entry.getValue()
            )));

        return sb.toString();
    }
}
