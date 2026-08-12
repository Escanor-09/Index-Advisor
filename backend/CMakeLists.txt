cmake_minimum_required(VERSION 3.16)

project(IndexAdvisor)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(PkgConfig REQUIRED)
find_package(nlohmann_json REQUIRED)
find_package(OpenSSL REQUIRED)

pkg_check_modules(PQXX REQUIRED libpqxx)

include_directories(
    include
    api
    external
    ${PQXX_INCLUDE_DIRS}
    ${PROJECT_SOURCE_DIR}/libpg_query
)

link_directories(
    ${PQXX_LIBRARY_DIRS}
)

add_executable(
    advisor
    src/main.cpp
    src/PostgresManager.cpp
    src/SQLParser.cpp
    src/QueryPlan.cpp
    src/CandidateGenerator.cpp
    src/IndexEvaluator.cpp
    src/QueryAdvisor.cpp
    src/RecommendationEngine.cpp
    api/Server.cpp
    src/AIExplanationService.cpp
    src/ExistingIndexManager.cpp
)

target_link_libraries(
    advisor
    PRIVATE
    ${PQXX_LIBRARIES}
    nlohmann_json::nlohmann_json
    ${PROJECT_SOURCE_DIR}/libpg_query/libpg_query.a

    OpenSSL::SSL
    OpenSSL::Crypto
)

target_compile_definitions(
    advisor
    PRIVATE
    CPPHTTPLIB_OPENSSL_SUPPORT
)