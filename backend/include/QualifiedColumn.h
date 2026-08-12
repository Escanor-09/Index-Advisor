#pragma once

#include <string>

struct QualifiedColumn
{
    std::string tableName;
    std::string columnName;
    bool descending = false;
};