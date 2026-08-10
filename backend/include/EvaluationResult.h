#pragma once
#include "CandidateIndex.h"
#include <string>

struct EvaluationResult
{
    CandidateIndex candidate;

    double beforeCost;
    double afterCost;

    double improvement;
};