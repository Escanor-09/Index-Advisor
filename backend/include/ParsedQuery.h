#pragma once

#include "JoinColumn.h"
#include "QualifiedColumn.h"
#include <string>
#include <vector>

struct ParsedQuery
{
    std::string tableName;
    std::vector<std::string> tables;

    std::vector<QualifiedColumn> filterColumns;
    std::vector<QualifiedColumn> orderByColumns;
    std::vector<JoinColumn> joinColumns;
};