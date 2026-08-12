#include "ExistingIndexManager.h"
#include "PostgresManager.h"
#include <sstream>

#include <pqxx/pqxx>

ExistingIndexManager::ExistingIndexManager(PostgresManager &db)
    : db_(db)
{
}

bool ExistingIndexManager::isPrefix(const std::vector<std::string> &small, const std::vector<std::string> &large)
{
    if (small.size() > large.size())
        return false;

    for (size_t i = 0; i < small.size(); i++)
    {
        if (small[i] != large[i])
            return false;
    }

    return true;
}

bool ExistingIndexManager::isCoveredByExistingIndex(const CandidateIndex &candidate, const std::vector<ExistingIndex> &existingIndexes)
{
    for (const auto &existing : existingIndexes)
    {
        if (existing.tableName != candidate.tableName)
            continue;

        if (isPrefix(candidate.columns, existing.columns))
            return true;
    }

    return false;
}

std::vector<ExistingIndex> ExistingIndexManager::getExistingIndexes()
{
    std::vector<ExistingIndex> indexes;

    pqxx::work txn(db_.getConnection());

    auto result = txn.exec(R"(
        SELECT
            tablename,
            indexdef
        FROM pg_indexes
        WHERE schemaname='public'
    )");

    for (const auto &row : result)
    {
        std::string indexDef = row["indexdef"].c_str();

        size_t tableStart = indexDef.find("public.");
        size_t tableEnd = indexDef.find(" USING");

        std::string tableName = indexDef.substr(tableStart + 7, tableEnd - (tableStart + 7));

        size_t leftParen = indexDef.rfind('(');
        size_t rightParen = indexDef.rfind(')');

        std::string cols = indexDef.substr(leftParen + 1, rightParen - leftParen - 1);

        std::vector<std::string> columns;

        std::stringstream ss(cols);

        std::string col;

        while (std::getline(ss, col, ','))
        {
            col.erase(0, col.find_first_not_of(" "));
            col.erase(col.find_last_not_of(" ") + 1);

            columns.push_back(col);
        }

        ExistingIndex idx;

        idx.tableName = tableName;
        idx.columns = columns;

        indexes.push_back(idx);
    }

    txn.commit();

    return indexes;
}