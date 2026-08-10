#pragma once
#include <string>
#include <nlohmann/json.hpp>

class QueryPlan
{
public:
    explicit QueryPlan(const std::string &jsonPlan);
    std::string getNodeType() const;
    std::string getRelationName() const;
    double getStartupCost() const;
    double getTotalCost() const;
    double getPlanRows() const;
    double getPlanWidth() const;
    double getActualStartupTime() const;
    double getActualTotalTime() const;
    double getActualRows() const;
    double getActualLoops() const;
    double getPlanningTime() const;
    double getExecutionTime() const;

private:
    nlohmann::json plan_;
};