#pragma once

#include "JoinColumn.h"
#include <string>
#include <vector>

struct ParsedQuery
{
    std::string tableName;

    std::vector<std::string> filterColumns;
    std::vector<std::string> orderByColumns;
    std::vector<JoinColumn> joinColumns;
};