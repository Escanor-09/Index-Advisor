# Index Advisor — Test Results

Real output from actually running the suites, not a hand-written summary. Regenerate by re-running the commands below — do not hand-edit numbers in this file without re-running the real command first, per this project's whole "measure, don't assume" discipline.

---

## Backend — `mvn test`

**Last run:** 2026-08-16, against local PostgreSQL 17.10 (Homebrew, `ecom_test` sandbox database), JDK 21.0.1 (Microsoft build), Maven (`spring-boot-starter-parent` 3.5.3).

**Command:** `cd backend && mvn test`

```
[INFO] Running advisor.recommendation.RecommendationEngineTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running advisor.candidates.CandidateGeneratorTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running advisor.candidates.CandidateIndexTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running advisor.candidates.ExistingIndexManagerIntegrationTest        (real database)
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running advisor.web.WorkloadAnalyzeResponseTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running advisor.planning.QueryPlanTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running advisor.QueryAdvisorValidationTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running advisor.WorkloadQueryAdvisorIntegrationTest                  (real database)
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running advisor.evaluation.IndexEvaluatorIntegrationTest             (real database)
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running advisor.evaluation.IndexEvaluatorLogicTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running advisor.workload.WorkloadHypoScreenerIntegrationTest         (real database, hypopg)
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running advisor.workload.WorkloadRecommendationEngineTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running advisor.parsing.SqlParserTest
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running advisor.WorkloadQueryAdvisorValidationTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0

[INFO] Results:
[INFO] Tests run: 94, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  3.846 s
```

**94/94 passing, 0 failures, 0 errors.** 13 test classes; 5 of them (`ExistingIndexManagerIntegrationTest`, `WorkloadQueryAdvisorIntegrationTest`, `IndexEvaluatorIntegrationTest`, `WorkloadHypoScreenerIntegrationTest`, and one more counted below) are `@Tag("integration")` and run against a real local Postgres — nothing about Postgres's own behavior is mocked anywhere in this suite. `WorkloadHypoScreenerIntegrationTest` additionally requires the `hypopg` extension (`CREATE EXTENSION hypopg;`).

Every integration test class cleans up after itself (`@AfterAll` drops any temporary index it created) — verified zero leftover `idx_experiment_*`/test indexes after this run, consistent with every prior run across the project's history.

---

## Frontend — `npm run build`

**Last run:** 2026-08-16, Node/Vite (`index-advisor/`).

**Command:** `cd index-advisor && npm run build`

```
vite v8.2.1 building client environment for production...
✓ 678 modules transformed.
dist/index.html                   0.46 kB │ gzip:   0.29 kB
dist/assets/index-DmKYM1uy.css    9.75 kB │ gzip:   2.06 kB
dist/assets/index-JVCh5iVV.js   648.71 kB │ gzip: 197.50 kB
✓ built in 287ms
```

Clean build, no errors. One pre-existing advisory (not a failure): the JS bundle is a single ~649 kB chunk, above Vite's 500 kB code-splitting warning threshold — not addressed, since this project has never needed route-based code splitting at its current size.

---

## Benchmarks (not pass/fail tests — real measured numbers)

Full methodology and results live in their own files, since they're measurement experiments rather than assertions:

- **[`SCALABILITY_BENCHMARK.md`](SCALABILITY_BENCHMARK.md)** — HypoPG-screened vs. real-only evaluation pipeline timing across sample workloads. Includes the write-overhead benchmark (INSERT/UPDATE/DELETE cost of a recommended index), added 2026-08-16.

---

## What this suite does and doesn't cover

Stated plainly, consistent with `CURRENT_PROGRESS.md`'s Known Limitations:

- **Covered:** unit-level correctness (parsing, candidate generation, recommendation filtering/ranking/pruning), and real-database integration behavior (actual `CREATE`/`DROP INDEX` cycles, actual `EXPLAIN ANALYZE`, actual HypoPG hypothetical-index sessions, actual schema-allowlist rejection of a crafted SQL-injection payload).
- **Not covered by this suite:** sustained concurrent load (the HikariCP naming-collision bug was found via one 5-concurrent-request manual test, not an automated load test), adversarial/pathological query shapes, and — until the write-overhead benchmark added 2026-08-16 — any write-path cost measurement at all.
- **Single machine, single point in time.** Exact timings are not expected to reproduce bit-for-bit elsewhere; the plan shapes and qualitative conclusions (which candidate wins, which gets filtered out) are what's been cross-checked repeatedly and do reproduce.
