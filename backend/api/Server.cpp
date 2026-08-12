#include "Server.h"
#include "httplib.h"
#include "nlohmann/json.hpp"
#include "AIExplanationService.h"

using json = nlohmann::json;

#include <iostream>

Server::Server() : db_("host=aws-0-ap-northeast-1.pooler.supabase.com "
                       "port=5432 "
                       "dbname=ecom_test "
                       "user=postgres.ygvlwydjwflfhaksmowa "
                       "password=Escanor09@07"),
                   advisor_(db_)
{
}

void Server::handleAnalyze(const httplib::Request &req, httplib::Response &res)
{
    json request = json::parse(req.body);

    std::string query = request["query"];

    auto recommendation = advisor_.analyze(query);

    json response;

    if (recommendation.allResults.empty())
    {
        response["hasRecommendation"] = false;

        response["bestIndex"] = "";

        response["originalCost"] = 0;

        response["bestCost"] = 0;

        response["improvement"] = 0;

        response["candidates"] = json::array();

        json analysis;

        analysis["tables"] = recommendation.queryInfo.tableName;

        analysis["filterColumns"] = json::array();

        for (const auto &filterCol : recommendation.queryInfo.filterColumns)
        {
            json fc;

            fc["table"] = filterCol.tableName;
            fc["column"] = filterCol.columnName;

            analysis["filterColumns"].push_back(fc);
        }

        analysis["joinColumns"] = json::array();

        for (const auto &joinCol : recommendation.queryInfo.joinColumns)
        {
            json jc;

            jc["table"] = joinCol.tableName;
            jc["column"] = joinCol.columnName;

            analysis["joinColumns"].push_back(jc);
        }

        analysis["orderByColumns"] = nlohmann::json::array();

        for (const auto &orderCol : recommendation.queryInfo.orderByColumns)
        {
            json oc;

            oc["table"] = orderCol.tableName;
            oc["column"] = orderCol.columnName;

            analysis["orderByColumns"].push_back(oc);
        }

        response["analysis"] = analysis;

        response["reasoning"] = recommendation.reasoning;

        res.set_content(response.dump(4), "application/json");

        return;
    }

    std::string indexString = recommendation.bestResult.candidate.tableName + "(";

    const auto &coulmns = recommendation.bestResult.candidate.columns;

    for (size_t i = 0; i < coulmns.size(); i++)
    {
        indexString += coulmns[i];

        if (i != coulmns.size() - 1)
        {
            indexString += ", ";
        }
    }

    indexString += ")";

    response["bestIndex"] = indexString;

    response["originalCost"] = recommendation.bestResult.beforeCost;

    response["bestCost"] = recommendation.bestResult.afterCost;

    response["improvement"] = recommendation.bestResult.improvement;

    json candidates = json::array();

    for (const auto &result : recommendation.allResults)
    {
        std::string candidateIndex = "(";

        for (size_t i = 0; i < result.candidate.columns.size(); i++)
        {
            candidateIndex += result.candidate.columns[i];

            if (i != result.candidate.columns.size() - 1)
            {
                candidateIndex += ", ";
            }
        }

        candidateIndex += ')';

        json candidate;

        candidate["index"] = candidateIndex;
        candidate["cost"] = result.afterCost;
        candidate["improvement"] = result.improvement;

        candidates.push_back(candidate);
    }

    response["candidates"] = candidates;

    json analysis;
    analysis["tables"] = recommendation.queryInfo.tables;

    analysis["filterColumns"] = nlohmann::json::array();

    for (const auto &filterCol : recommendation.queryInfo.filterColumns)
    {
        json fc;

        fc["table"] = filterCol.tableName;
        fc["column"] = filterCol.columnName;

        analysis["filterColumns"].push_back(fc);
    }

    analysis["joinColumns"] = json::array();

    for (const auto &joinCol : recommendation.queryInfo.joinColumns)
    {
        json jc;

        jc["table"] = joinCol.tableName;
        jc["column"] = joinCol.columnName;

        analysis["joinColumns"].push_back(jc);
    }

    analysis["orderByColumns"] = nlohmann::json::array();

    for (const auto &orderCol : recommendation.queryInfo.orderByColumns)
    {
        json oc;

        oc["table"] = orderCol.tableName;
        oc["column"] = orderCol.columnName;

        analysis["orderByColumns"].push_back(oc);
    }

    response["analysis"] = analysis;

    response["reasoning"] = recommendation.reasoning;

    res.set_content(response.dump(4), "application/json");
}

void Server::handleGenerateExplanation(const httplib::Request &req, httplib::Response &res)
{
    json request = json::parse(req.body);

    AIExplanationService aiService;

    std::vector<std::string> reasoning = aiService.generate(request.dump(4));

    json response;

    response["reasoning"] = reasoning;

    res.set_content(response.dump(4), "application/json");
}

void Server::start()
{
    httplib::Server server;

    server.set_pre_routing_handler([](const httplib::Request &, httplib::Response &res)
                                   {
            res.set_header("Access-Control-Allow-Origin","*");

            res.set_header("Access-Control-Allow-Headers","Content-Type");

            res.set_header("Access-Control-Allow-Methods","GET, POST, OPTIONS");

            return httplib::Server::HandlerResponse::Unhandled; });

    server.Options(R"(.*)", [](const httplib::Request &req, httplib::Response &res)
                   { res.status = 200; });

    server.Get("/", [](const httplib::Request &, httplib::Response &res)
               { res.set_content("Index Advisor Running", "text/plain"); });

    server.Post("/api/analyze-query", [this](const httplib::Request &req, httplib::Response &res)
                { handleAnalyze(req, res); });

    server.Post("/api/generate-explanation", [this](const httplib::Request &req, httplib::Response &res)
                { handleGenerateExplanation(req, res); });

    server.listen("0.0.0.0", 8080);
}