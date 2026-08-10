#include "QueryAdvisor.h"

QueryAdvisor::QueryAdvisor(PostgresManager &db) : evaluator_(db) {}

Recommendation QueryAdvisor::analyze(const std::string &query)
{
    ParsedQuery parsed = parser_.parse(query);

    std::vector<CandidateIndex> candidates = generator_.generate(parsed);

    std::vector<EvaluationResult> results;

    for (const auto &candidate : candidates)
    {
        results.push_back(evaluator_.evaluate(query, candidate));
    }

    return recommender_.recommend(results);
}