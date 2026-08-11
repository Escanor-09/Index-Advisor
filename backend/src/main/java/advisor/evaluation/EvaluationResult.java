package advisor.evaluation;

import advisor.candidates.CandidateIndex;

public record EvaluationResult(
    CandidateIndex candidate,
    String beforeNodeType,
    String afterNodeType,
    double beforeCost,
    double afterCost,
    double beforeExecutionTime,
    double afterExecutionTime,
    double improvementPercent,
    long indexSizeBytes
) {}
