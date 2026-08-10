#include "RecommendationEngine.h"

Recommendation RecommendationEngine::recommend(const std::vector<EvaluationResult> &results)
{
    Recommendation recommendation;

    if (results.empty())
        return recommendation;

    EvaluationResult bestResult = results[0];

    for (size_t i = 1; i < results.size(); i++)
    {
        if (results[i].improvement > bestResult.improvement)
            bestResult = results[i];
    }

    recommendation.bestResult = bestResult;

    return recommendation;
}