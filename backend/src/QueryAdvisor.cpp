#include "QueryAdvisor.h"
#include "ExistingIndexManager.h"
#include "ExistingIndex.h"
// #include "AIExplanationService.h"
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
    // AIExplanationService aiService;
    ParsedQuery parsed = parser_.parse(query);
    // std::cout
    //     << "Parsed table: "
    //     << parsed.tableName
    //     << std::endl;
    std::vector<CandidateIndex> candidates = generator_.generate(parsed);

    ExistingIndexManager existingIndexManager(db_);

    auto indexes = existingIndexManager.getExistingIndexes();

    std::cout << "\nExisting Indexes:\n";

    for (const auto &idx : indexes)
    {
        std::cout << idx.tableName << "(";

        for (size_t i = 0;
             i < idx.columns.size();
             i++)
        {
            std::cout << idx.columns[i];

            if (i + 1 != idx.columns.size())
                std::cout << ", ";
        }

        std::cout << ")\n";
    }

    std::vector<EvaluationResult> results;

    for (const auto &candidate : candidates)
    {
        if (isExistingIndex(candidate, indexes))
        {

            std::cout << "Skipping existing index: ";

            std::cout << candidate.tableName << "(";

            for (size_t i = 0; i < candidate.columns.size(); i++)
            {
                std::cout << candidate.columns[i];

                if (i + 1 != candidate.columns.size())
                    std::cout << ", ";
            }
            std::cout << ")\n";
            continue;
        }

        if (existingIndexManager.isCoveredByExistingIndex(candidate, indexes))
        {
            std::cout << "Skipping covered index: ";

            std::cout << candidate.tableName << "(";

            for (size_t i = 0; i < candidate.columns.size(); i++)
            {
                std::cout << candidate.columns[i];

                if (i + 1 != candidate.columns.size())
                    std::cout << ", ";
            }

            std::cout << ")\n";

            continue;
        }

        // auto start = std::chrono::steady_clock::now();
        results.push_back(evaluator_.evaluate(query, candidate));
        // auto end = std::chrono::steady_clock::now();
        //  std::cout
        //      << "EVALUATE(): "
        //      << std::chrono::duration_cast<
        //             std::chrono::milliseconds>(
        //             end - start)
        //             .count()
        //      << " ms\n";
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

    // nlohmann::json advisorData;

    // advisorData["query"] = query;
    // advisorData["table"] = recommendation.bestResult.candidate.tableName;
    // advisorData["columns"] = recommendation.bestResult.candidate.columns;
    // advisorData["beforeCost"] = recommendation.bestResult.beforeCost;
    // advisorData["afterCost"] = recommendation.bestResult.afterCost;
    // advisorData["improvement"] = recommendation.bestResult.improvement;

    // recommendation.reasoning = aiService.generate(advisorData.dump(4));
    recommendation.reasoning = {"AI explanation pending"};
    return recommendation;
}