#pragma once

#include <string>

#include "Recommendation.h"
#include "SQLParser.h"
#include "CandidateGenerator.h"
#include "IndexEvaluator.h"
#include "RecommendationEngine.h"
#include "ExistingIndex.h"

class QueryAdvisor
{
public:
    QueryAdvisor(PostgresManager &db);
    Recommendation analyze(const std::string &query);
    bool isExistingIndex(const CandidateIndex &candidate, const std::vector<ExistingIndex> &existingIndexes);

private:
    PostgresManager &db_;
    SQLParser parser_;
    CandidateGenerator generator_;
    IndexEvaluator evaluator_;
    RecommendationEngine recommender_;
};