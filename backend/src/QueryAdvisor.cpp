#include "QueryAdvisor.h"
#include "ExistingIndexManager.h"
#include "ExistingIndex.h"
#include <iostream>

QueryAdvisor::QueryAdvisor(PostgresManager &db) : db_(db), evaluator_(db) {}

bool QueryAdvisor::isExistingIndex(const CandidateIndex &candidate, const std::vector<ExistingIndex> &existingIndexes)
{

    for (const auto &existing : existingIndexes)
    {
        if (candidate.tableName != existing.tableName)
            continue;

        if (candidate.columns == existing.columns)
            return true;
    }
    return false;
}

Recommendation QueryAdvisor::analyze(const std::string &query)
{

    ParsedQuery parsed = parser_.parse(query);
    std::vector<CandidateIndex> candidates = generator_.generate(parsed);

    ExistingIndexManager existingIndexManager(db_);

    auto indexes = existingIndexManager.getExistingIndexes();

    std::vector<EvaluationResult> results;

    for (const auto &candidate : candidates)
    {

        if (isExistingIndex(candidate, indexes))
        {
            continue;
        }

        if (existingIndexManager.isCoveredByExistingIndex(candidate, indexes))
        {
            continue;
        }

        // auto start = std::chrono::steady_clock::now();
        results.push_back(evaluator_.evaluate(query, candidate));
    }

    if (results.empty())
    {
        Recommendation recommendation;

        recommendation.queryInfo = parsed;

        recommendation.reasoning = {
            "No recommenation generated. Existing indexes already cover this query."};
        return recommendation;
    }

    results = recommender_.pruneDominated(results);

    Recommendation recommendation = recommender_.recommend(results);
    recommendation.queryInfo = parsed;

    recommendation.reasoning = {"AI explanation pending"};
    return recommendation;
}