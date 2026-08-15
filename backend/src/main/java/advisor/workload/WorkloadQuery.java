package advisor.workload;

import advisor.parsing.ParsedQuery;
import advisor.planning.QueryPlan;

public record WorkloadQuery(String sql, ParsedQuery parsedQuery, QueryPlan queryPlan) {}
