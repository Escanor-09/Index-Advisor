package advisor.evaluation;

import advisor.candidates.CandidateIndex;

/**
 * Result of screening a candidate via HypoPG: only the planner's estimated
 * cost, since a hypothetical index is invisible to real query execution
 * (there is no real "execution time" to measure). See HypoIndexEvaluator.
 */
public record HypoEvaluationResult(
    CandidateIndex candidate,
    String beforeNodeType,
    String afterNodeType,
    double beforeCost,
    double afterCost,
    double estimatedCostImprovementPercent
) {}
