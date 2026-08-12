#include "PostgresManager.h"
#include <iostream>
#include <sstream>
#include <chrono>

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

    // auto start = std::chrono::steady_clock::now();
    pqxx::result result = txn.exec("EXPLAIN (FORMAT JSON) " + query);
    // auto end = std::chrono::steady_clock::now();
    return result[0][0].c_str();
}

void PostgresManager::execute(const std::string &query)
{
    pqxx::work txn(conn);
    // auto start = std::chrono::steady_clock::now();
    txn.exec(query);
    txn.commit();
    // auto end = std::chrono::steady_clock::now();
}

pqxx::connection &PostgresManager::getConnection()
{
    return conn;
}