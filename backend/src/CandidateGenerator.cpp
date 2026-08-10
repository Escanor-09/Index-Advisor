#include "CandidateGenerator.h"

std::vector<CandidateIndex> CandidateGenerator::generate(const ParsedQuery &query)
{
    std::vector<CandidateIndex> candidates;

    for (const auto &col : query.filterColumns)
    {
        CandidateIndex idx;

        idx.tableName = query.tableName;
        idx.columns.push_back(col);

        candidates.push_back(idx);
    }
    return candidates;
}