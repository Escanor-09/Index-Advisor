#pragma once

#include <string>

#include "Recommendation.h"
#include "SQLParser.h"
#include "CandidateGenerator.h"
#include "IndexEvaluator.h"
#include "RecommendationEngine.h"

class QueryAdvisor
{
public:
    QueryAdvisor(PostgresManager &db);
    Recommendation analyze(const std::string &query);

private:
    SQLParser parser_;
    CandidateGenerator generator_;
    IndexEvaluator evaluator_;
    RecommendationEngine recommender_;
};