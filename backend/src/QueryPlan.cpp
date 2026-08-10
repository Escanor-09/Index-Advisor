#include "QueryPlan.h"

QueryPlan::QueryPlan(const std::string &jsonPlan) : plan_(nlohmann::json::parse(jsonPlan)) {}

std::string QueryPlan::getNodeType() const
{
    return plan_[0]["Plan"]["Node Type"];
}

std::string QueryPlan::getRelationName() const
{
    return plan_[0]["Plan"]["Relation Name"];
}

double QueryPlan::getStartupCost() const
{
    return plan_[0]["Plan"]["Startup Cost"];
}

double QueryPlan::getTotalCost() const
{
    return plan_[0]["Plan"]["Total Cost"];
}

double QueryPlan::getPlanRows() const
{
    return plan_[0]["Plan"]["Plan Rows"];
}

double QueryPlan::getPlanWidth() const
{
    return plan_[0]["Plan"]["Plan Width"];
}

double QueryPlan::getActualStartupTime() const
{
    return plan_[0]["Plan"]["Actual Startup Time"];
}

double QueryPlan::getActualTotalTime() const
{
    return plan_[0]["Plan"]["Actual Total Time"];
}

double QueryPlan::getActualRows() const
{
    return plan_[0]["Plan"]["Actual Rows"];
}

double QueryPlan::getActualLoops() const
{
    return plan_[0]["Plan"]["Actual Loops"];
}

double QueryPlan::getPlanningTime() const
{
    return plan_[0]["Planning Time"];
}

double QueryPlan::getExecutionTime() const
{
    return plan_[0]["Execution Time"];
}