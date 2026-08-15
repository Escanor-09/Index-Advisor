package advisor.workload;

import java.util.ArrayList;
import java.util.List;

import advisor.candidates.CandidateIndex;
import advisor.database.PostgresManager;
import advisor.evaluation.EvaluationResult;
import advisor.evaluation.IndexEvaluator;

/**
 * Evaluates a candidate against every workload query it could plausibly
 * help, not just the single query it was generated from (that narrower
 * scope is what Milestone 12's QueryAdvisor does).
 */
public class WorkloadIndexEvaluator {

    private final IndexEvaluator evaluator;

    public WorkloadIndexEvaluator(PostgresManager db) {
        this.evaluator = new IndexEvaluator(db);
    }

    public WorkloadEvaluationResult evaluate(CandidateIndex candidate, WorkloadProfile profile) {
        List<QueryEvaluation> queryEvaluations = new ArrayList<>();
        double workloadScore = 0.0;

        for (WorkloadQuery wq : profile.queries()) {
            if (candidate.appliesTo(wq.parsedQuery())) {
                EvaluationResult result = evaluator.evaluate(candidate, wq.sql());
                queryEvaluations.add(new QueryEvaluation(wq.sql(), result));
                workloadScore += result.improvementPercent();
            }
        }

        return new WorkloadEvaluationResult(candidate, queryEvaluations, workloadScore);
    }

    public List<WorkloadEvaluationResult> evaluateAll(List<CandidateIndex> candidates, WorkloadProfile profile) {
        return candidates.stream().map(candidate -> evaluate(candidate, profile)).toList();
    }
}
