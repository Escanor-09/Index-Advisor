#include "PostgresManager.h"
#include <iostream>
#include <sstream>

PostgresManager::PostgresManager(const std::string &connectionString) : conn(connectionString) {}

bool PostgresManager::testConnection()
{
    if (conn.is_open())
    {
        std::cout << "Connected to " << conn.dbname() << "\n";
        return true;
    }
    return false;
}

std::string PostgresManager::executeScalar(const std::string &query)
{
    pqxx::work txn(conn);

    pqxx::result result = txn.exec(query);

    txn.commit();

    if (result.empty())
        return "";

    return result[0][0].c_str();
}

std::string PostgresManager::explainAnalyze(const std::string &query)
{
    pqxx::work txn(conn);

    pqxx::result result = txn.exec("EXPLAIN (ANALYZE, FORMAT JSON) " + query);

    return result[0][0].c_str();
}

void PostgresManager::execute(const std::string &query)
{
    pqxx::work txn(conn);
    txn.exec(query);
    txn.commit();
}