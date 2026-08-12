#pragma once
#include "ExistingIndex.h"
#include "CandidateIndex.h"
#include <string>

class PostgresManager;

class ExistingIndexManager
{
public:
    ExistingIndexManager(PostgresManager &db);

    std::vector<ExistingIndex> getExistingIndexes();

    bool isCoveredByExistingIndex(const CandidateIndex &candidate, const std::vector<ExistingIndex> &existingIndexes);
    bool isPrefix(const std::vector<std::string> &small, const std::vector<std::string> &large);

private:
    PostgresManager &db_;
};