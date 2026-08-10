#pragma once

#include "CandidateIndex.h"
#include "EvaluationResult.h"
#include "PostgresManager.h"

class IndexEvaluator
{
public:
    explicit IndexEvaluator(PostgresManager &db);

    EvaluationResult evaluate(const std::string &query, const CandidateIndex &candidate);

private:
    PostgresManager &db_;
    std::string generateIndexName(const CandidateIndex &candidate);
    std::string buildCreateIndexSQL(const CandidateIndex &candidate, const std::string &indexName);
    std::string buildDropIndexSQL(const std::string &indexName);
    double calculateImprovement(double beforeCost, double afterCost);
};