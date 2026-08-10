#pragma once

#include <string>
#include <vector>

struct ParsedQuery
{
    std::string tableName;

    std::vector<std::string> filterColumns;
};