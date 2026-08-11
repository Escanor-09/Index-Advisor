package advisor.workload;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import advisor.database.PostgresManager;
import advisor.parsing.ParsedQuery;
import advisor.parsing.SqlParser;
import advisor.planning.QueryPlan;

public class WorkloadAdvisor {

    public static WorkloadProfile analyzeWorkload(List<String> sqlQueries, PostgresManager db) {
        List<WorkloadQuery> workloadQueries = new ArrayList<>();
        Map<String, Integer> columnFrequency = new LinkedHashMap<>();

        for (String sql : sqlQueries) {
            ParsedQuery parsed = SqlParser.parse(sql);
            QueryPlan plan = new QueryPlan(db.explainAnalyze(sql));

            workloadQueries.add(new WorkloadQuery(sql, parsed, plan));

            for (String column : parsed.filterColumns()) {
                String key = parsed.tableName() + "." + column;
                columnFrequency.merge(key, 1, Integer::sum);
            }
        }

        return new WorkloadProfile(workloadQueries, columnFrequency);
    }
}
