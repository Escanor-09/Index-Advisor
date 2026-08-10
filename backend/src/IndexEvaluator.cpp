#include "IndexEvaluator.h"
#include "QueryPlan.h"

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

EvaluationResult IndexEvaluator::evaluate(const std::string &query, const CandidateIndex &candidate)
{

    QueryPlan beforePlan(db_.explainAnalyze(query));

    double beforeCost = beforePlan.getTotalCost();

    std::string indexName = generateIndexName(candidate);

    std::string createSQL = buildCreateIndexSQL(candidate, indexName);

    db_.execute(createSQL);

    QueryPlan afterPlan(db_.explainAnalyze(query));

    double afterCost = afterPlan.getTotalCost();

    std::string dropSQL = buildDropIndexSQL(indexName);

    db_.execute(dropSQL);

    EvaluationResult result;

    result.candidate = candidate;
    result.beforeCost = beforeCost;
    result.afterCost = afterCost;
    result.improvement = calculateImprovement(beforeCost, afterCost);

    return result;
}