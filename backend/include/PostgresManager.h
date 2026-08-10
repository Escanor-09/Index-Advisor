#pragma once

#include <pqxx/pqxx>
#include <string>

class PostgresManager
{
private:
    pqxx::connection conn;

public:
    PostgresManager(const std::string &connectionString);

    bool testConnection();

    void execute(const std::string &sql);

    std::string explainAnalyze(const std::string &query);

    std::string executeScalar(const std::string &query);
};