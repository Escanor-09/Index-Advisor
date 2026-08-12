#pragma once
#include "PostgresManager.h"
#include "QueryAdvisor.h"
#include "httplib.h"

class Server
{
public:
    Server();
    void start();

private:
    PostgresManager db_;
    QueryAdvisor advisor_;

    void handleAnalyze(const httplib::Request &req, httplib::Response &res);
    void handleGenerateExplanation(const httplib::Request &req, httplib::Response &res);
};