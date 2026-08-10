#pragma once
#include <nlohmann/json.hpp>
#include <string>

#include "ParsedQuery.h"

class SQLParser
{
public:
    ParsedQuery parse(const std::string &query);
    void extractColumns(const nlohmann::json &node, std::vector<std::string> &columns);
};