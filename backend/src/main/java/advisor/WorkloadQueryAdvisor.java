package advisor;

import java.util.List;

import advisor.candidates.CandidateGenerator;
import advisor.candidates.CandidateIndex;
import advisor.database.PostgresManager;
import advisor.parsing.ParsedQuery;
import advisor.recommendation.Recommendation;
import advisor.workload.WorkloadAdvisor;
import advisor.workload.WorkloadEvaluationResult;
import advisor.workload.WorkloadIndexEvaluator;
import advisor.workload.WorkloadProfile;
import advisor.workload.WorkloadQuery;
import advisor.workload.WorkloadRecommendationEngine;

/**
 * Top-level workload orchestrator: ingest -> profile -> generate
 * candidates -> evaluate each across the whole workload -> choose an
 * index SET (not just each query's individually-best answer).
 */
public class WorkloadQueryAdvisor {

    private final PostgresManager db;

    public WorkloadQueryAdvisor(PostgresManager db) {
        this.db = db;
    }

    public List<Recommendation> analyzeWorkload(List<String> sqlQueries) {
        WorkloadProfile profile = WorkloadAdvisor.analyzeWorkload(sqlQueries, db);

        List<ParsedQuery> allParsed = profile.queries().stream().map(WorkloadQuery::parsedQuery).toList();
        List<CandidateIndex> candidates = CandidateGenerator.generateForWorkload(allParsed);

        WorkloadIndexEvaluator evaluator = new WorkloadIndexEvaluator(db);
        List<WorkloadEvaluationResult> results = evaluator.evaluateAll(candidates, profile);

        return WorkloadRecommendationEngine.chooseIndexSet(results);
    }
}
