#include "IndexEvaluator.h"
#include "QueryPlan.h"
#include <iostream>

IndexEvaluator::IndexEvaluator(PostgresManager &db) : db_(db) {}

std::string IndexEvaluator::generateIndexName(const CandidateIndex &candidate)
{
    std::string generatedIdxName = candidate.tableName;
    for (auto &name : candidate.columns)
    {
        generatedIdxName += "_" + name;
    }

    generatedIdxName += "_idx";
    return generatedIdxName;
}

std::string IndexEvaluator::buildCreateIndexSQL(const CandidateIndex &candidate, const std::string &indexName)
{
    std::string sql = "CREATE INDEX " + indexName + " ON " + candidate.tableName + "(";
    for (size_t i = 0; i < candidate.columns.size(); i++)
    {
        sql += candidate.columns[i];

        if (i != candidate.columns.size() - 1)
        {
            sql += ", ";
        }
    }
    sql += ");";
    return sql;
}

std::string IndexEvaluator::buildDropIndexSQL(const std::string &indexName)
{
    return "DROP INDEX " + indexName + ";";
}

double IndexEvaluator::calculateImprovement(double beforeCost, double afterCost)
{
    return beforeCost - afterCost;
}

std::string IndexEvaluator::buildHypoPGCreateSQL(const CandidateIndex &candidate)
{
    std::string indexDef = "CREATE INDEX ON " + candidate.tableName + "(";

    for (size_t i = 0; i < candidate.columns.size(); i++)
    {
        indexDef += candidate.columns[i];

        if (i != candidate.columns.size() - 1)
        {
            indexDef += ", ";
        }
    }

    indexDef += ")";
    return "SELECT * FROM hypopg_create_index('" + indexDef + "');";
}

std::string IndexEvaluator::buildHypoPGResetSQL()
{
    return "SELECT hypopg_reset();";
}

EvaluationResult IndexEvaluator::evaluate(const std::string &query, const CandidateIndex &candidate)
{

    try
    {

        auto start = std::chrono::steady_clock::now();

        pqxx::work txn(db_.getConnection());

        pqxx::result beforeResult = txn.exec("EXPLAIN (FORMAT JSON) " + query);

        QueryPlan beforePlan(beforeResult[0][0].c_str());

        double beforeCost = beforePlan.getTotalCost();

        std::string createSQL = buildHypoPGCreateSQL(candidate);

        txn.exec(createSQL);

        pqxx::result afterResult = txn.exec("EXPLAIN (FORMAT JSON) " + query);

        QueryPlan afterPlan(afterResult[0][0].c_str());

        double afterCost = afterPlan.getTotalCost();

        txn.exec(buildHypoPGResetSQL());

        txn.commit();

        auto end = std::chrono::steady_clock::now();

        EvaluationResult result;

        result.candidate = candidate;
        result.beforeCost = beforeCost;
        result.afterCost = afterCost;
        result.improvement = calculateImprovement(beforeCost, afterCost);

        return result;
    }
    catch (const std::exception &e)
    {
        std::cerr << "\nEVALUATION ERROR\n"
                  << e.what() << "\n";
        throw;
    }
}