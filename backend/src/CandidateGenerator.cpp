#include "CandidateGenerator.h"
#include <iostream>
#include <functional>

static void generateCombinations(const std::vector<std::string> &columns, size_t start, size_t maxWidth, std::vector<std::string> &current, std::vector<std::vector<std::string>> &result)
{
    if (!current.empty())
    {
        result.push_back(current);
    }

    if (current.size() == maxWidth)
        return;

    for (size_t i = start; i < columns.size(); i++)
    {
        current.push_back(columns[i]);

        generateCombinations(columns, i + 1, maxWidth, current, result);

        current.pop_back();
    }
}

std::vector<CandidateIndex> CandidateGenerator::generate(const ParsedQuery &query)
{
    if (query.filterColumns.size() > 8)
    {
        std::cerr << "too many filter columns\n";
    }
    std::vector<CandidateIndex> candidates;

    constexpr size_t MAX_INDEX_WIDTH = 3;

    std::vector<std::string> candidateColumns = query.filterColumns;

    for (const auto &joinCol : query.joinColumns)
    {
        if (joinCol.tableName != query.tableName)
            continue;

        if (std::find(candidateColumns.begin(), candidateColumns.end(), joinCol.columnName) == candidateColumns.end())
        {
            candidateColumns.push_back(joinCol.columnName);
        }
    }

    for (const auto &col : query.orderByColumns)
    {
        if (std::find(candidateColumns.begin(), candidateColumns.end(), col) == candidateColumns.end())
        {
            candidateColumns.push_back(col);
        }
    }

    std::vector<std::vector<std::string>> combinations;
    std::vector<std::string> current;

    generateCombinations(candidateColumns, 0, MAX_INDEX_WIDTH, current, combinations);

    for (const auto &cols : combinations)
    {
        CandidateIndex idx;

        idx.tableName = query.tableName;
        idx.columns = cols;

        candidates.push_back(idx);
    }

    std::cout << "\nGenerated Candidates:\n";

    for (const auto &candidate : candidates)
    {
        std::cout << candidate.tableName << "(";

        for (size_t i = 0; i < candidate.columns.size(); i++)
        {
            std::cout << candidate.columns[i];

            if (i != candidate.columns.size() - 1)
                std::cout << ", ";
        }

        std::cout << ")\n";
    }
    return candidates;
}
