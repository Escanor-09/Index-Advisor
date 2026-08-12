#include "RecommendationEngine.h"

bool RecommendationEngine::isPrefix(const std::vector<std::string> &small, const std::vector<std::string> &large)
{
    if (small.size() > large.size())
        return false;

    for (size_t i = 0; i < small.size(); i++)
    {
        if (small[i] != large[i])
            return false;
    }
    return true;
}

std::vector<EvaluationResult> RecommendationEngine::pruneDominated(const std::vector<EvaluationResult> &results)
{
    std::vector<EvaluationResult> filtered;

    for (size_t i = 0; i < results.size(); i++)
    {
        bool dominated = false;

        for (size_t j = 0; j < results.size(); j++)
        {
            if (i == j)
                continue;

            const auto &a = results[j];
            const auto &b = results[i];

            if (isPrefix(a.candidate.columns, b.candidate.columns) && a.improvement >= b.improvement)
            {
                dominated = true;
                break;
            }
        }
        if (!dominated)
            filtered.push_back(results[i]);
    }
    return filtered;
}

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

    recommendation.allResults = results;

    return recommendation;
}