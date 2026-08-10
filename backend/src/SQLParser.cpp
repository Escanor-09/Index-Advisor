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

    parsed.tableName = ast["stmts"][0]["stmt"]["SelectStmt"]["fromClause"][0]["RangeVar"]["relname"];

    auto &whereClause = ast["stmts"][0]["stmt"]["SelectStmt"]["whereClause"];

    if (!whereClause.is_null())
    {
        extractColumns(whereClause, parsed.filterColumns);
    }

    std::sort(parsed.filterColumns.begin(), parsed.filterColumns.end());
    parsed.filterColumns.erase(std::unique(parsed.filterColumns.begin(), parsed.filterColumns.end()), parsed.filterColumns.end());

    pg_query_free_parse_result(result);

    return parsed;
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

                if (fields.size() > 0 &&
                    fields[0].contains("String"))
                {
                    std::string column = fields[0]["String"]["sval"];

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