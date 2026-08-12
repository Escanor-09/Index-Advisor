#include "CandidateGenerator.h"
#include <iostream>
#include <algorithm>
#include <unordered_map>
#include <vector>

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

    std::vector<CandidateIndex> candidates;
    constexpr size_t MAX_INDEX_WIDTH = 3;

    struct TableColBuckets
    {
        std::vector<std::string> filtersAndJoins;
        std::vector<std::string> orderBy;
    };

    std::unordered_map<std::string, TableColBuckets> tableBuckets;

    for (const auto &col : query.filterColumns)
    {
        auto &vec = tableBuckets[col.tableName].filtersAndJoins;
        if (std::find(vec.begin(), vec.end(), col.columnName) == vec.end())
        {
            vec.push_back(col.columnName);
        }
    }

    for (const auto &col : query.joinColumns)
    {
        auto &vec = tableBuckets[col.tableName].filtersAndJoins;
        if (std::find(vec.begin(), vec.end(), col.columnName) == vec.end())
        {
            vec.push_back(col.columnName);
        }
    }

    for (const auto &col : query.orderByColumns)
    {
        auto &buckets = tableBuckets[col.tableName];
        bool inFilters = std::find(buckets.filtersAndJoins.begin(), buckets.filtersAndJoins.end(), col.columnName) != buckets.filtersAndJoins.end();
        if (!inFilters)
        {
            if (std::find(buckets.orderBy.begin(), buckets.orderBy.end(), col.columnName) == buckets.orderBy.end())
            {
                buckets.orderBy.push_back(col.columnName);
            }
        }
    }

    for (const auto &[table, buckets] : tableBuckets)
    {
        std::vector<std::vector<std::string>> filterCombinations;
        std::vector<std::string> current;
        generateCombinations(buckets.filtersAndJoins, 0, MAX_INDEX_WIDTH, current, filterCombinations);

        for (const auto &cols : filterCombinations)
        {
            candidates.push_back({table, cols});
        }

        for (const auto &fCol : buckets.filtersAndJoins)
        {
            for (const auto &oCol : buckets.orderBy)
            {
                if (fCol != oCol)
                {
                    candidates.push_back({table, {fCol, oCol}});
                }
            }
        }

        std::vector<std::vector<std::string>> orderCombinations;
        current.clear();
        generateCombinations(buckets.orderBy, 0, MAX_INDEX_WIDTH, current, orderCombinations);
        for (const auto &cols : orderCombinations)
        {
            candidates.push_back({table, cols});
        }
    }

    return candidates;
}