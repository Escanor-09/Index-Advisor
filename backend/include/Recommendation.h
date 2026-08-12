#pragma once

#include "EvaluationResult.h"
#include "ParsedQuery.h"

struct Recommendation
{
    EvaluationResult bestResult;

    std::vector<EvaluationResult> allResults;
    std::vector<std::string> reasoning;

    ParsedQuery queryInfo;
};