#pragma once
#include <string>
#include <vector>

class AIExplanationService
{
public:
    AIExplanationService();
    std::vector<std::string> generate(const std::string &advisorJson);

private:
    std::string apiKey_;
};