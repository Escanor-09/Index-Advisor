#pragma once

#include <vector>

#include "ParsedQuery.h"
#include "CandidateIndex.h"

class CandidateGenerator
{
public:
    std::vector<CandidateIndex> generate(const ParsedQuery &);
};