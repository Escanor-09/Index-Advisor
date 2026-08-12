#include "SQLParser.h"

#include <pg_query.h>
#include <nlohmann/json.hpp>
#include <iostream>

ParsedQuery SQLParser::parse(const std::string &query)
{
    ParsedQuery parsed;

    std::unordered_map<std::string, std::string> aliasMap;

    PgQueryParseResult result = pg_query_parse(query.c_str());

    if (result.error)
    {
        pg_query_free_parse_result(result);

        return parsed;
    }

    auto ast = nlohmann::json::parse(result.parse_tree);

    try
    {

        auto &selectStmt = ast["stmts"][0]["stmt"]["SelectStmt"];

        auto &fromItem = selectStmt["fromClause"][0];

        if (fromItem.contains("RangeVar"))
        {
            parsed.tableName = fromItem["RangeVar"]["relname"];
            parsed.tables.push_back(parsed.tableName);
        }

        else if (fromItem.contains("JoinExpr"))
        {
            auto &joinExpr = fromItem["JoinExpr"];

            parsed.tableName = findLeftmostTable(joinExpr["larg"]);

            collectAliases(joinExpr, aliasMap);

            for (const auto &[alias, table] : aliasMap)
            {
                parsed.tables.push_back(table);
            }

            extractJoinColumns(joinExpr, parsed.joinColumns, aliasMap);
        }

        if (selectStmt.contains("whereClause"))
        {
            auto &whereClause = selectStmt["whereClause"];

            if (!whereClause.is_null())
            {
                extractColumns(whereClause, parsed.filterColumns, aliasMap, parsed.tableName);
            }
        }

        if (selectStmt.contains("sortClause"))
        {
            auto &sortClause = selectStmt["sortClause"];

            if (!sortClause.is_null())
            {
                extractOrderByColumns(
                    sortClause,
                    parsed.orderByColumns,
                    parsed,
                    aliasMap);
            }
        }

        pg_query_free_parse_result(result);

        return parsed;
    }
    catch (const std::exception &e)
    {
        std::cout << "PARSER EXECEPTION " << e.what() << "\\n";
        return parsed;
    }
    return parsed;
}

std::string SQLParser::findLeftmostTable(const nlohmann::json &node)
{
    if (node.contains("RangeVar"))
        return node["RangeVar"]["relname"];

    if (node.contains("JoinExpr"))
        return findLeftmostTable(node["JoinExpr"]["larg"]);

    return "";
}

void SQLParser::collectAliases(const nlohmann::json &node, std::unordered_map<std::string, std::string> &aliasMap)
{
    if (!node.is_object())
        return;

    if (node.contains("RangeVar"))
    {
        auto &rv = node["RangeVar"];

        if (rv.contains("alias"))
        {
            aliasMap[rv["alias"]["aliasname"]] = rv["relname"];
        }
    }

    for (auto &[k, v] : node.items())
    {
        collectAliases(v, aliasMap);
    }
}

void SQLParser::extractOrderByColumns(const nlohmann::json &sortClause, std::vector<QualifiedColumn> &columns, const ParsedQuery &parsed, const std::unordered_map<std::string, std::string> &aliasMap)
{

    if (!sortClause.is_array())
        return;

    for (const auto &sortItem : sortClause)
    {
        if (!sortItem.contains("SortBy"))
            continue;

        auto &sortBy = sortItem["SortBy"];

        if (!sortBy.contains("node") || !sortBy["node"].contains("ColumnRef"))
            continue;

        auto &fields = sortBy["node"]["ColumnRef"]["fields"];
        QualifiedColumn qc;

        if (fields.size() == 1)
        {
            qc.columnName = fields[0]["String"]["sval"];
            qc.tableName = parsed.tableName; // Use main table name fallback
        }
        else if (fields.size() == 2)
        {
            std::string aliasOrTable = fields[0]["String"]["sval"];
            qc.columnName = fields[1]["String"]["sval"];

            if (aliasMap.count(aliasOrTable))
            {
                qc.tableName = aliasMap.at(aliasOrTable);
            }
            else
            {
                qc.tableName = parsed.tableName.empty() ? aliasOrTable : parsed.tableName;
            }
        }

        if (sortBy.contains("sortby_dir") && sortBy["sortby_dir"].is_string())
        {
            std::string dir = sortBy["sortby_dir"];
            qc.descending = (dir == "SORTBY_DESC");
        }

        if (!qc.columnName.empty() && !qc.tableName.empty())
        {
            columns.push_back(qc);
        }
    }
}

void SQLParser::extractAliases(const nlohmann::json &node, std::unordered_map<std::string, std::string> &aliasMap)
{

    if (!node.is_object())
        return;

    if (node.contains("RangeVar"))
    {
        auto &rangeVar = node["RangeVar"];

        if (rangeVar.contains("alias"))
        {
            aliasMap[rangeVar["alias"]["aliasname"]] = rangeVar["relname"];
        }
    }

    for (auto &[key, value] : node.items())
    {
        extractAliases(value, aliasMap);
    }
}

void SQLParser::extractJoinColumns(const nlohmann::json &node, std::vector<JoinColumn> &columns, const std::unordered_map<std::string, std::string> &aliasMap)
{
    if (!node.is_object())
        return;

    if (node.contains("ColumnRef"))
    {
        auto &columnRef = node["ColumnRef"];

        if (columnRef.contains("fields"))
        {
            auto &fields = columnRef["fields"];

            if (fields.size() == 2 && fields[0].contains("String") && fields[1].contains("String"))
            {
                JoinColumn jc;

                std::string alias = fields[0]["String"]["sval"];

                jc.tableName = aliasMap.at(alias);

                jc.columnName = fields[1]["String"]["sval"];

                columns.push_back(jc);
            }
        }
    }

    for (auto &[key, value] : node.items())
    {
        extractJoinColumns(value, columns, aliasMap);
    }
}

void SQLParser::extractColumns(const nlohmann::json &node, std::vector<QualifiedColumn> &columns, const std::unordered_map<std::string, std::string> &aliasMap, const std::string &defaultTable)
{
    if (node.is_object())
    {

        if (node.contains("ColumnRef"))
        {
            auto &columnRef = node["ColumnRef"];

            if (columnRef.contains("fields"))
            {
                auto &fields = columnRef["fields"];

                if (!fields.empty() &&
                    fields.back().contains("String"))
                {
                    QualifiedColumn qc;

                    if (fields.size() == 1)
                    {
                        qc.columnName = fields[0]["String"]["sval"];
                        qc.tableName = defaultTable;
                    }
                    else if (fields.size() == 2)
                    {
                        std::string alias = fields[0]["String"]["sval"];

                        if (aliasMap.count(alias))
                            qc.tableName = aliasMap.at(alias);

                        qc.columnName = fields[1]["String"]["sval"];
                    }

                    if (!qc.columnName.empty() && !qc.tableName.empty())
                    {
                        columns.push_back(qc);
                    }
                }
            }
        }

        for (auto &[key, value] : node.items())
        {
            extractColumns(value, columns, aliasMap, defaultTable);
        }
    }
    else if (node.is_array())
    {
        for (auto &element : node)
        {
            extractColumns(element, columns, aliasMap, defaultTable);
        }
    }
}