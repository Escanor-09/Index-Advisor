#pragma once

#include <vector>

#include "EvaluationResult.h"
#include "Recommendation.h"

class RecommendationEngine
{
public:
    Recommendation recommend(const std::vector<EvaluationResult> &results);
};