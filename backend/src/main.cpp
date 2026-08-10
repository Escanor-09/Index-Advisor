#include "PostgresManager.h"
#include "QueryAdvisor.h"

#include <iostream>

int main()
{
    PostgresManager db(
        "host=aws-0-ap-northeast-1.pooler.supabase.com "
        "port=5432 "
        "dbname=ecom_test "
        "user=postgres.ygvlwydjwflfhaksmowa "
        "password=Escanor09@07");

    QueryAdvisor advisor(db);

    Recommendation recommendation = advisor.analyze("SELECT * FROM products WHERE brand = 'Apple';");

    std::cout << "Recommended Index:\n";

    std::cout << recommendation.bestResult.candidate.tableName << "(";

    const auto &columns = recommendation.bestResult.candidate.columns;

    for (size_t i = 0; i < columns.size(); i++)
    {
        std::cout << columns[i];

        if (i != columns.size() - 1)
        {
            std::cout << ", ";
        }
    }

    std::cout << ")\n";

    std::cout << "Improvement: " << recommendation.bestResult.improvement << "\n";

    return 0;
}