package advisor.workload;

import java.util.List;

import advisor.candidates.CandidateIndex;

/**
 * Raw measurement, not a decision: every applicable query this candidate
 * was evaluated against, including queries where it didn't help or made
 * things worse. workloadScore is the sum of improvementPercent across all
 * of them (negatives included) — a quick-glance signal only. Filtering and
 * set selection happen downstream in WorkloadRecommendationEngine.
 */
public record WorkloadEvaluationResult(
    CandidateIndex candidate,
    List<QueryEvaluation> queryEvaluations,
    double workloadScore
) {}
