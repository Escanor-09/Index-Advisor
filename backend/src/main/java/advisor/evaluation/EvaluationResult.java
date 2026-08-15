package advisor.evaluation;

import advisor.candidates.CandidateIndex;

public record EvaluationResult(
    CandidateIndex candidate,
    String beforeNodeType,
    String afterNodeType,
    // Full-plan-shape diff (QueryPlan.describeChange), not just root-to-root — for a
    // join, root can stay identical while the real change is a nested scan; beforeNodeType/
    // afterNodeType alone can't distinguish "nothing changed" from "root just didn't move".
    String observedEffect,
    double beforeCost,
    double afterCost,
    double beforeExecutionTime,
    double afterExecutionTime,
    double improvementPercent,
    long indexSizeBytes
) {}
