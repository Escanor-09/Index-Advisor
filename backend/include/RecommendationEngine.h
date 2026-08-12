#pragma once

#include <vector>

#include "EvaluationResult.h"
#include "Recommendation.h"

class RecommendationEngine
{
public:
    Recommendation recommend(const std::vector<EvaluationResult> &results);
    std::vector<EvaluationResult> pruneDominated(const std::vector<EvaluationResult> &results);
    bool isPrefix(const std::vector<std::string> &small, const std::vector<std::string> &large);
};