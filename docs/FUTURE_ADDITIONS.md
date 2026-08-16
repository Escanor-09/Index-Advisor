# Index Advisor — Future Improvements by ROI

## 5/5 ROI — Highest Priority

- Complete the React frontend and make the complete system usable end-to-end.
- ~~Complete Docker containerization for the frontend, backend, and database environment.~~ **Done** — `Dockerfile`s for both services + `docker-compose.yml`; the database itself is intentionally not containerized since both DB tiers are already Supabase-hosted.
- ~~Add CI/CD with automated build, test, integration-test, and deployment pipelines.~~ **Done** — GitHub Actions (`.github/workflows/ci.yml`) builds a real Postgres + HypoPG image, restores a versioned schema, regenerates the dataset, and runs the full 94-test suite on every push/PR to `main`/`consolidated`; frontend build runs alongside it.
- Wire HypoPG into the actual workload-selection pipeline as a screen-then-real-validation architecture.
- Add a quantitative index-cost model combining measured performance benefit, storage cost, and write-maintenance cost.
- ~~Measure INSERT, UPDATE, and DELETE overhead caused by candidate indexes.~~ **Done, honestly inconclusive** — `WriteOverheadBenchmark` (see `SCALABILITY_BENCHMARK.md`) found the real per-statement cost is inside the noise floor of client-side timing at this project's current 10,000-row scale. Not yet wired into live `Recommendation.tradeoffs` — the number isn't clean enough to show a user yet. Directly motivates the scale-up item just below.
- Add an explicit storage/index-count budget to workload-level index selection.
- Build a large realistic workload benchmark instead of relying on only 10 hand-written queries.
- Create multiple workload scenarios representing different days and different access-pattern distributions.
- Benchmark advisor runtime and recommendation behavior as workload size and database size increase.
- Validate the advisor against an independent real-world relational database such as the Olist Brazilian E-Commerce dataset.
- Run the same advisor pipeline against both the controlled project database and the independent real-world database without database-specific logic.
- Produce a final scalability benchmark comparing real-index evaluation against HypoPG screening and the hybrid pipeline.

## 4/5 ROI — Strong Improvements

- Increase the benchmark database size while keeping the existing small database for rapid development and regression testing.
- Scale the database with realistic relational ratios instead of increasing every table to the same row count.
- Create approximately 10 workload stories with roughly 1,000 queries per scenario.
- Add realistic temporal workload variation such as order-heavy, registration-heavy, product-heavy, and other workload patterns.
- Add realistic low-, medium-, and high-selectivity columns and predicates.
- Improve selectivity-based composite-index column ordering instead of alphabetical ordering.
- Improve the greedy index-set selection algorithm to account for incremental benefit rather than binary query coverage.
- Add a small-workload comparison against an exact optimization method to evaluate the greedy heuristic.
- Add ORDER BY awareness to query parsing and candidate generation.
- Add GROUP BY awareness to query parsing and candidate generation.
- Improve JOIN support for multi-condition and non-equality JOIN predicates.
- Add support for three-table and larger JOIN chains.
- Improve Recommendation.observedEffect so nested plan-node changes are represented correctly.
- Add API-level and end-to-end tests for the complete HTTP pipeline.
- Add concurrent-load and realistic performance testing for the HTTP API.
- Add reproducible benchmark scripts for all scalability experiments.
- Document and publish benchmark results comparing planner estimates, real execution measurements, and HypoPG predictions.

## 3/5 ROI — Valuable but Secondary

- Add support for subqueries.
- Add support for CTEs.
- Add HAVING awareness to query parsing and candidate generation.
- Improve composite-index candidate generation beyond the current single-column-plus-full-composite strategy.
- Model PostgreSQL's leftmost-prefix behavior more completely when evaluating composite indexes.
- Add support for partial indexes.
- Add support for covering indexes using INCLUDE columns.
- Add support for expression indexes.
- Add support for additional PostgreSQL index types such as BRIN, GIN, and GiST where workload characteristics justify them.
- Add realistic temporal distributions to database timestamp columns.
- Add weekday, weekend, and seasonal data distributions.
- Add NULL values to genuinely optional columns where appropriate.
- Improve remaining product/category distribution realism.
- Add adversarial and pathological query cases.
- Add more realistic workloads derived from publicly available database workloads where possible.
- Add a proper application logging framework instead of relying on System.out.println.
- Unify CLI and Spring Boot database configuration into one configuration mechanism.
- Improve application configuration and secret management for production deployment.
- Add database-state isolation and cleanup guarantees to all automated integration tests.
- Improve API validation and error handling for unsupported SQL and malformed workloads.
- Add API documentation.

## 2/5 ROI — Nice-to-Have / Advanced

- Add a workload-level estimate of cumulative query-time savings from each recommended index.
- Replace fixed recommendation thresholds with configurable or data-driven thresholds.
- Add experiment/result persistence for historical benchmark comparisons.
- Add a frontend dashboard showing workload statistics, candidate counts, evaluation times, and recommendation quality.
- Add interactive execution-plan visualization and deeper before/after plan inspection.
- Add richer product-description data if text-search indexing is eventually explored.
- ~~Add automated deployment to a production-like cloud environment.~~ **Done** — backend live on Render, frontend live on Vercel, both auto-deploying on push; verified end-to-end (live `/analyze` call, CORS preflight from the real origin, deployed bundle confirmed pointing at the live backend).
- Compare the advisor quantitatively against existing index-advisor approaches and tools.
- Add broader PostgreSQL-version compatibility testing.
- Add long-running stress tests with very large workloads and databases.
- Add fault-injection testing for database failures, connection failures, and interrupted index experiments.

## 1/5 ROI — Low Priority / Only If Time Remains

- Add support for highly advanced SQL constructs outside the project's core workload model.
- Add sophisticated global index-set optimization beyond the practical needs of the benchmark.
- Add extensive support for specialized PostgreSQL index features not exercised by the target workloads.
- Add large-scale distributed benchmarking across multiple machines.
- Add advanced historical workload learning and automatic workload-pattern discovery.
- Add automatic tuning of recommendation thresholds from benchmark results.
- Add a full research-grade optimizer for globally optimal index-set selection.
- Add extensive visualization and analytics features beyond what is needed for the placement demo.
