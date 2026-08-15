package advisor.workload;

import advisor.evaluation.EvaluationResult;

/** One candidate's measured effect on one specific workload query. */
public record QueryEvaluation(String sql, EvaluationResult result) {}
