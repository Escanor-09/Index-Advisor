cmake_minimum_required(VERSION 3.16)

project(IndexAdvisor)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(PkgConfig REQUIRED)
find_package(nlohmann_json REQUIRED)

pkg_check_modules(PQXX REQUIRED libpqxx)

include_directories(
    include
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
)

target_link_libraries(
    advisor
    PRIVATE
    ${PQXX_LIBRARIES}
    nlohmann_json::nlohmann_json
    ${PROJECT_SOURCE_DIR}/libpg_query/libpg_query.a
)