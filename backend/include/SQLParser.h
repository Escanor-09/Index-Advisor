#pragma once
#include <nlohmann/json.hpp>
#include <string>

#include "QualifiedColumn.h"
#include "ParsedQuery.h"

class SQLParser
{
public:
    ParsedQuery parse(const std::string &query);

private:
    void extractColumns(const nlohmann::json &node, std::vector<QualifiedColumn> &columns, const std::unordered_map<std::string, std::string> &aliasMap, const std::string &defaultTable);
    void extractOrderByColumns(const nlohmann::json &sortClause, std::vector<QualifiedColumn> &columns, const ParsedQuery &parsed, const std::unordered_map<std::string, std::string> &aliasMap);
    void extractJoinColumns(const nlohmann::json &node, std::vector<JoinColumn> &columns, const std::unordered_map<std::string, std::string> &aliasMap);
    void extractAliases(const nlohmann::json &node, std::unordered_map<std::string, std::string> &aliasMap);
    void collectAliases(const nlohmann::json &node, std::unordered_map<std::string, std::string> &aliasMap);
    std::string findLeftmostTable(const nlohmann::json &node);
};