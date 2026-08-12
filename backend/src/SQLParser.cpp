#include "SQLParser.h"

#include <pg_query.h>
#include <nlohmann/json.hpp>
#include <iostream>

ParsedQuery SQLParser::parse(const std::string &query)
{
    ParsedQuery parsed;

    PgQueryParseResult result = pg_query_parse(query.c_str());

    if (result.error)
    {
        pg_query_free_parse_result(result);

        return parsed;
    }

    auto ast = nlohmann::json::parse(result.parse_tree);

    std::cout << ast.dump(4) << std::endl;

    auto &selectStmt = ast["stmts"][0]["stmt"]["SelectStmt"];

    auto &fromItem = selectStmt["fromClause"][0];

    if (fromItem.contains("RangeVar"))
        parsed.tableName = fromItem["RangeVar"]["relname"];
    else if (fromItem.contains("JoinExpr"))
    {
        auto &joinExpr = fromItem["JoinExpr"];

        parsed.tableName = joinExpr["larg"]["RangeVar"]["relname"];

        std::unordered_map<std::string, std::string> aliasMap;

        auto &left = joinExpr["larg"]["RangeVar"];
        auto &right = joinExpr["rarg"]["RangeVar"];

        aliasMap[left["alias"]["aliasname"]] = left["relname"];

        aliasMap[right["alias"]["aliasname"]] = right["relname"];

        extractJoinColumns(joinExpr["quals"], parsed.joinColumns, aliasMap);
    }

    auto &whereClause = selectStmt["whereClause"];

    if (!whereClause.is_null())
    {
        extractColumns(whereClause, parsed.filterColumns);
    }

    // extractJoinColumns(selectStmt, parsed.joinColumns);

    auto &sortClause = selectStmt["sortClause"];

    if (!sortClause.is_null())
    {
        extractOrderByColumns(sortClause, parsed.orderByColumns);
    }

    std::sort(parsed.filterColumns.begin(), parsed.filterColumns.end());
    parsed.filterColumns.erase(std::unique(parsed.filterColumns.begin(), parsed.filterColumns.end()), parsed.filterColumns.end());

    pg_query_free_parse_result(result);

    std::cout << "\nORDER BY Columns:\n";

    for (const auto &col : parsed.orderByColumns)
    {
        std::cout << col << std::endl;
    }

    std::cout << "\nJOIN Columns:\n";

    for (const auto &col : parsed.joinColumns)
    {
        std::cout << col.tableName << "." << col.columnName << "\n";
    }

    return parsed;
}

void SQLParser::extractOrderByColumns(const nlohmann::json &sortClause, std::vector<std::string> &columns)
{
    for (const auto &sortItem : sortClause)
    {
        if (!sortItem.contains("SortBy"))
            continue;

        auto &sortBy = sortItem["SortBy"];

        if (!sortBy.contains("node"))
            continue;

        auto &node = sortBy["node"];

        if (!node.contains("ColumnRef"))
            continue;

        auto &columnRef = node["ColumnRef"];

        if (!columnRef.contains("fields"))
            continue;

        auto &fields = columnRef["fields"];

        if (fields.size() > 0 &&
            fields[0].contains("String"))
        {
            columns.push_back(
                fields[0]["String"]["sval"]);
        }
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

void SQLParser::extractColumns(const nlohmann::json &node, std::vector<std::string> &columns)
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
                    std::string column =
                        fields.back()["String"]["sval"];

                    columns.push_back(column);
                }
            }
        }

        for (auto &[key, value] : node.items())
        {
            extractColumns(value, columns);
        }
    }
    else if (node.is_array())
    {
        for (auto &element : node)
        {
            extractColumns(element, columns);
        }
    }
}