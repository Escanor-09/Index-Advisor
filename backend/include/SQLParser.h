#pragma once
#include <nlohmann/json.hpp>
#include <string>

#include "ParsedQuery.h"

class SQLParser
{
public:
    ParsedQuery parse(const std::string &query);
    void extractColumns(const nlohmann::json &node, std::vector<std::string> &columns);
    void extractOrderByColumns(const nlohmann::json &sortClause, std::vector<std::string> &columns);
    void extractJoinColumns(const nlohmann::json &node, std::vector<JoinColumn> &columns, const std::unordered_map<std::string, std::string> &aliasMap);
};