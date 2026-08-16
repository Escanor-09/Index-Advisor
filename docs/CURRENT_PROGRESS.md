# Index Advisor — Current Progress

What has been built, verified, and what's left. For *why* things are designed the way they are, see `ARCHITECTURE.md`. For interview-ready Q&A, see `INTERVIEW_NOTES.md`. All three docs (plus `FUTURE_ADDITIONS.md` and `FRONTEND_README.md`) now live together under `docs/` — nothing project-relevant is scattered at the repo root anymore.

## Snapshot

- **Goal:** analyze SQL queries/workloads and recommend PostgreSQL indexes, using Postgres's own `EXPLAIN ANALYZE` as ground truth instead of a custom cost model.
- **Stack:** Java 21, Spring Boot 3.5.3, HikariCP-pooled JDBC, JSqlParser 5.3, Jackson, PostgreSQL, HypoPG extension. React (Vite) frontend fully connected — both single-query and workload analysis are live against the real backend.
- **Completion: 97%** on the original 16-milestone roadmap (milestone-weighted, see Roadmap below) — plus a growing list of off-roadmap priority improvements: quantitative storage cost, connection pooling, a 94-test automated suite, HypoPG-based screening (now genuinely load-bearing in the live workload pipeline, not just a demo), JOIN support, ORDER BY-aware candidates/existing-index awareness/prefix-domination pruning/AI-generated explanations, an explicit storage/count budget for index-set selection, accurate nested-plan-change reporting, public-API request-size hardening, and — as of this round — a verified live deployment.
- **Core advisor engine — single-query and workload-level, with JOIN support, HypoPG-screened evaluation, and a real selection budget — is fully built, verified against a real database and a 94-test suite, exposed over a hardened HTTP API, and driving a real frontend for both analysis modes.** The system is now live: backend on Render, frontend on Vercel, verified end to end. Only write-overhead cost modeling remains on the original roadmap; CI/CD is the largest remaining off-roadmap gap (see `RUNDOWN.html`).

---

## What's Built

### Milestone 0 — Database Foundation — DONE
7 tables (`categories`, `customers`, `products`, `orders`, `order_items`, `payments`, `shipping_addresses`), 10,000 rows in every table except `order_items` (see Data Quality Improvements below — no longer 1:1 with `orders`), 16 constraints (7 PK, 9 FK). Node.js populate scripts in `src/populate/`. All 18 integrity checks pass (`npm run verify`) — row counts, no orphaned FKs, cross-field consistency (`payments.amount` = `orders.total_amount`, etc.), no duplicate emails/IDs, no NULLs in mandatory columns. Two Postgres targets: Supabase `ecom` (shared canonical) and local `ecom_test` (experimentation sandbox, restored from `database/ecom_dump_*.sql`; renamed from `index_advisor` on 16 Aug 2026 — see Local Dev Sync, below). Known schema gap: no `UNIQUE` constraint on `customers.email` at the DB level (uniqueness only holds because the generator constructs it that way).

### Data Quality Improvements (post-JOIN-support) — DONE

Decided, after comparing two independent recommendations (mine and a second AI's) that converged on the same conclusion: **fix data realism before increasing row count** — a skewed, realistic 10,000-row dataset demonstrates index value more convincingly than a uniform 1,000,000-row one, and costs nothing in iteration speed. Two changes, both confined to the Node.js generation layer (`src/populate/`), zero changes to the Java backend:

1. **`order_items` was artificially 1:1 with `orders`.** Every order had exactly one line item — unrealistic, and a real limit on how interesting JOIN/aggregate queries could be now that JOIN support exists. Fixed with a weighted 1–5 items/order distribution (30/30/20/12/8%), duplicate-product-per-order avoided via bounded retry. New `src/populate/random-utils.js` (`weightedChoice`).

2. **Customer and product activity were near-uniform, not skewed.** Real e-commerce activity is heavy-tailed — a small share of customers/products account for a disproportionate share of activity. Added `createZipfianSampler()` (rank-based weighted sampling, `random-utils.js`) and used it in `orders.js` (customer selection) and `order-items.js` (product selection).

**A real calibration mistake, caught by verifying actual output rather than trusting the math:** first attempt used exponents 1.1 (customers) / 1.2 (products), described as "moderate" without checking. Real result: one customer got 1,496 of 10,000 orders (15%), one product appeared in 4,113 of 23,719 line items (17%) — a single monopolizing outlier, not realistic skew. Recalibrated to 0.5/0.6 by directly querying top-10%-share and max-single-item concentration after each attempt, not by re-guessing exponents. Final, verified numbers:

```
Items per order:        1: 30-31%, 2: 29-30%, 3: 20%, 4: 12%, 5: 8%  (target 30/30/20/12/8 — matches closely)
Customer concentration: top 10% of customers → ~29% of orders (most active: 40-44 orders out of 10,000)
Product concentration:  top 10% of products → ~38% of order_items (most popular: 246-265 times)
order_items total:      ~23,600-23,900 (up from a fixed 10,000) — an organic consequence of the ratio fix,
                         not an independent size increase
```

Both numbers reproduced independently on two separate runs (local `index_advisor` and Supabase `ecom`, two different random realizations of the same generation logic) — 28.8%/37.9% locally, 28.5%/37.8% on Supabase. Consistent, not a fluke.

**Verified, not just generated:** re-ran the full 18-point `verify-data.js` integrity check (all pass — including on the new distribution: check #17, orders-per-customer, now shows a genuine long tail — 4,387 customers with zero orders, most with 1–2, out to 40 for the most active, versus the old near-uniform Poisson(1) shape). Re-ran all 42 JUnit tests and the full CLI demo against the refreshed local database — all pass, zero leftover experimental indexes. Applied the identical regeneration to Supabase (the documented "source of truth for generated data") only after local validation succeeded — truncating shared infrastructure is consequential, so local was used as a safe staging step first. Fresh `database/ecom_dump_2026-08-11.sql` taken and verified (all 7 tables' `COPY` statements present) after Supabase regeneration.

Deliberately not touched: `products.category_id` assignment (still uniform — not requested, kept in scope-bounded), `payments`/`shipping_addresses` (still correctly 1:1 with `orders` — that ratio is realistic, unlike the old `order_items` one), row counts on any table (explicitly deferred — see Known Limitations and the size-vs-quality discussion this decision came from).

### Milestone 1 — Language Decision — DONE
Java, final. Checked against an explicit rule (*if Java materially limits the final product, switch to Python*) — it doesn't, for anything built so far. One accepted trade-off: no Java equivalent of Python's `pglast` (a direct `libpg_query` binding), so `JSqlParser` (ANTLR-based, not Postgres-exact) is used instead. Mitigated by cross-checking against `EXPLAIN`'s own `Relation Name`/`Filter`/`Index Cond` fields when needed (not yet built, available as a fallback).

### Milestones 2–4 — `PostgresManager` (connectivity, query execution, EXPLAIN) — DONE
`testConnection()`, `executeScalar(String)`, `executeColumn(String)` (added Milestone 14), `explainAnalyze(String)`, `executeUpdate(String)` (added Milestone 10). Verified against local `index_advisor`: `SELECT COUNT(*) FROM categories` → `10000`; real `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` output.

### Milestone 5 — SQL Parsing — DONE
`ParsedQuery` (record: `tableName`, `filterColumns`) + `SqlParser` (JSqlParser 5.3, recursive WHERE-clause walk). Verified against 4 cases including parenthesized OR/AND.

**Bug found and fixed:** JSqlParser 5.x parses a parenthesized boolean group (`(status='a' OR status='b')`) as `ParenthesedExpressionList`, not the old `Parenthesis` node. First implementation only handled `Parenthesis` and silently dropped a column. Found by running `javap` against the actual downloaded jar rather than trusting memory of the API. Fixed by handling `ExpressionList` and dropping the deprecated `Parenthesis` branch entirely.

### Milestone 6 — QueryPlan Representation — DONE
`QueryPlan` wraps EXPLAIN JSON via Jackson tree model (`JsonNode`, not POJO binding). `getTotalCost()`, `getExecutionTime()`, `getPlanningTime()`, `getNodeType()`, `getRelationName()`.

### Milestone 7 — Workload Ingestion — DONE
`WorkloadReader` + `backend/workload/queries.sql` (10 hand-written queries, deliberately repeating `products.category_id`/`status`, `orders.customer_id`, `customers.status` so frequency analysis has real repetition).

### Milestone 8 — Workload Profiling — DONE
`WorkloadQuery`, `WorkloadProfile`, `WorkloadAdvisor`. Correctly computed (not hardcoded): `products.category_id` frequency 3, `products.status` frequency 2, rest frequency 1.

### Milestone 9 — Candidate Generation — DONE
`CandidateIndex`, `CandidateGenerator`. One single-column candidate per filter column + one full composite if >1 filter column. 9 distinct candidates across the 10-query workload (deduped via `LinkedHashSet`, relying on record structural equality).

### Milestone 10 — Index Evaluation — DONE (and hardened, see Priority Improvement below)
`EvaluationResult`, `IndexEvaluator`. Real `EXPLAIN ANALYZE` → `CREATE INDEX` → `EXPLAIN ANALYZE` → `DROP INDEX` cycle. Hard safety check (`current_database() = 'ecom_test'` or throw — renamed from `index_advisor` 16 Aug 2026, see Local Dev Sync below). Guaranteed cleanup (pre-emptive `DROP INDEX IF EXISTS` + `finally`-block drop). Verified zero leftover indexes after every run, every milestone, without exception.

Original result (products, category_id + status): `category_id` alone 92.0% improvement, `status` alone **-6.8%** (honest negative — not selective enough), composite 96.2%.

### Milestone 11 — RecommendationEngine — DONE
`Recommendation` (full explainability record — see `ARCHITECTURE.md`), `RecommendationEngine`. Filters on a real minimum (`improvementPercent >= 10.0`), ranks, tiers (HIGH ≥50%, MEDIUM ≥20%, LOW otherwise). Verified: composite ranked first, `category_id` alone second, `status` alone filtered out entirely.

### Milestone 12 — Single-Query Advisor (`QueryAdvisor`) — DONE
`analyze(String sql) -> List<Recommendation>`, wires `SqlParser → CandidateGenerator → IndexEvaluator → RecommendationEngine`. Tested against 3 different queries. `orders(status)` (5 roughly-even values) scored MEDIUM (49.4%) — a genuinely new, non-repeated data point vs. `products(status)` (3 values, ~66% weighted to `'active'`), which scored nothing. Real measured variance from real data distribution.

### Milestone 13 — Workload Advisor (index SET selection) — DONE
The algorithmically distinct milestone — everything else was one well-defined class; this is a real selection algorithm. `CandidateIndex.appliesTo()`, `QueryEvaluation`, `WorkloadEvaluationResult`, `WorkloadIndexEvaluator`, `WorkloadRecommendationEngine` (greedy set selection), `WorkloadQueryAdvisor` (orchestrator).

Real run, all 10 workload queries, 9 candidates evaluated across every query each applies to (~15 create/drop cycles): **chose 6 of 9 candidates.** Correctly excluded `products(category_id, status)` — its only query (Q4) was already covered by `products(category_id)` alone once that was chosen first (it covers 3 queries vs. the composite's 1, so it wins the greedy marginal-score comparison). This demonstrated a real, measured gap between per-query advice (Milestone 12 recommended both for Q4 in isolation) and workload-level advice (Milestone 13 recommends only one). Also correctly excluded `products(status)` and `customers(status)` (both low-selectivity).

### Milestone 14 — Spring Boot HTTP API — DONE
`pom.xml` now uses `spring-boot-starter-parent` 3.5.3 + `spring-boot-starter-web` (Jackson/Postgres driver now Spring-managed: 2.19.1 / 42.7.7). New `advisor.web` package: `IndexAdvisorApplication` (`@SpringBootApplication`, separate from `Main.java`'s CLI demo), `AdvisorConfig` (`PostgresManager` bean from `application.properties`), `AdvisorController` (`POST /analyze`, `POST /analyze-workload`, `@ExceptionHandler`-based 400/500 responses), 3 DTO records.

**Security fix done as part of this milestone, not deferred:** `IndexEvaluator` builds DDL via string concatenation (JDBC can't parameterize identifiers, only values). Investigated concretely: PostgreSQL's JDBC driver runs `Statement.execute()` via the simple query protocol, which permits multiple `;`-separated statements in one call — a crafted column name like `category_id; DROP TABLE customers; --`, extracted by `SqlParser` from caller-supplied SQL, could execute as a second statement. Fixed with two layers in `IndexEvaluator`: a schema allowlist (loaded once at construction from `information_schema.tables`/`.columns`, case-normalized to lowercase) and quoted identifiers in generated DDL as defense-in-depth.

**Verified with the actual attack, not just reasoning:**
- Valid `/analyze` and `/analyze-workload` requests → correct, correctly-ranked JSON (records serialize via Jackson with zero custom mapping code).
- Nonexistent column → `400 {"error":"Unknown column: products.nonexistent_column"}`.
- **The injection payload** — `SELECT * FROM products WHERE "category_id; DROP TABLE customers; --" = 25;` → `400`. Confirmed via `psql`: `customers` untouched, 10,000 rows.
- Non-`SELECT` statement → `400` via `SqlParser`'s pre-existing guard.
- Zero leftover `idx_experiment_*` indexes after the whole session.

**Bonus finding from this testing** (not a bug in Milestone 14's code): `products(status)` scored ~40% "improvement" via `/analyze` with `observedEffect` still `"Seq Scan -> Seq Scan"` — the plan hadn't changed. This surfaced the measurement bug fixed next.

### Priority Improvement — Measurement Methodology Fix — DONE
Ranked against a list of candidate improvements (JUnit tests, HypoPG, JOIN support, quantitative cost modeling, connection pooling, larger-scale benchmarking, ORDER BY/GROUP BY awareness, candidate ordering, logging/API polish) and picked as the single best effort-to-value ratio: smallest, most contained change (confined to `IndexEvaluator`), directly fixing an already-found, already-reproduced bug rather than a hypothetical one.

1. **`measureStable(String sql)`** — 1 discarded warm-up run + 3 measured runs per side, returns the plan with the *median* execution time (node type/cost don't vary run-to-run for a fixed schema state, only timing does). Applied identically before and after.
2. **Same-node-type clamp** — if `before.getNodeType().equals(after.getNodeType())`, `improvement` is clamped to `Math.min(improvement, 0.0)`. Added because the median-of-3 fix alone *reduced* the artifact (~40% → ~16.7%) but didn't eliminate it — `CREATE INDEX`'s own table-scan happens *between* the before/after measurement blocks, accumulating extra cache warmth that a same-side warm-up can't neutralize. If Postgres didn't change strategy, there's no mechanism for a real speedup, so any residual positive delta is noise, not signal.

**Verified across all three states**, re-testing the exact scenario that exposed the bug:
```
Before any fix:                 ~40%   improvement, "Seq Scan -> Seq Scan"   (the bug)
After measureStable alone:      ~16.7% improvement, "Seq Scan -> Seq Scan"   (reduced, not fixed)
After the clamp too:            ~-5.6% improvement, "Seq Scan -> Seq Scan"   (matches original -6.8% finding)
```
Re-ran the full CLI demo afterward: `WorkloadRecommendationEngine` again correctly excludes `products(status)`/`customers(status)`; `QueryAdvisor` again correctly excludes `products(status)` from Q4 (back to exactly 2 recommendations, not 3). The bug had been quietly corrupting actual set-selection and filtering decisions, not just cosmetic numbers — this fix restored correct behavior, verified by outcome, not just by re-reading the code. Zero leftover indexes throughout. Cost: 8 `EXPLAIN ANALYZE` calls per `evaluate()` instead of 2 — still low tens-of-milliseconds total, no perceptible slowdown.

### Milestone 15 — Frontend Integration & Backend Feature Parity — DONE

The project's frontend turned out to already exist — a teammate had built both a React (Vite) frontend and an independent **C++ backend** (`libpg_query` for parsing, `libpqxx`, HypoPG-only evaluation, `cpp-httplib` HTTP server), sitting on the repo's `main` branch, unrelated git history to this project's `backend` branch. Pulled in (`git archive` into a scratch dir first, to inspect without touching the working tree), then read in full — not skimmed — before deciding anything.

**Comparison, by actually reading both codebases, not by reputation:**

| | His C++ backend | This Java backend |
|---|---|---|
| Parsing | `libpg_query` (real Postgres parser) — more robust | JSqlParser (3rd-party) — a real bug already found/fixed here |
| Scope | Single query only | Single query **and** workload-level (greedy set selection) |
| Evaluation | HypoPG only — planner cost *estimate*, never runs real DDL | Real `CREATE INDEX` + `EXPLAIN ANALYZE` — measured, not estimated |
| ORDER BY | Yes | No (until this round) |
| Existing-index awareness | Yes (`pg_indexes` + prefix coverage) | No (until this round) |
| Redundant-candidate pruning | Yes (prefix domination) | No (until this round) |
| Storage cost | Not measured | Measured (`pg_relation_size`) |
| Concurrency safety | One `pqxx::connection` shared across a multi-threaded HTTP server — looks thread-unsafe | HikariCP pool — a real collision bug already found/fixed here |
| SQL-injection defense on dynamic DDL | None — identifiers concatenated raw | Schema allowlist + quoted identifiers, tested against a crafted payload |
| Tests | None found | 51 tests before this round |
| AI explanations | Yes (Gemini) | No (until this round) |
| Credentials | **Hardcoded in `Server.cpp`, committed to the public repo** | Externalized via `application.properties` |

**Security finding, unrelated to the comparison itself:** `backend/api/Server.cpp` (his code) hardcodes live Supabase credentials — including the same password as this project's own local `.env` — directly in source, pushed to the public GitHub repo. Flagged immediately; rotating it is a Supabase-account action for the project owner, not something done from this session.

**Decision:** hybrid, not a wholesale pick. Java stays the core engine for single-query analysis (real measured proof, workload-level reasoning his code doesn't have at all, and the safety/concurrency/test hardening that came from bugs actually found and fixed in this codebase) — Java's workload advisor was never in contention, since his code has no workload endpoint at all. His two genuine feature gaps (ORDER BY-aware candidates, existing-index awareness) plus his prefix-domination pruning and AI-explanation UX were ported natively into Java rather than via a cross-language microservice call — less total engineering than standing up a second live service for something this small, and his code stays untouched in the repo/git history rather than being discarded.

**What was built, each verified against the real local database, not assumed correct:**

1. **`SqlParser`/`ParsedQuery`: ORDER BY extraction.** New `qualifiedOrderByColumns` field (table-attributed, same shape as `qualifiedFilterColumns`), extracted via JSqlParser's `OrderByElement`/`getOrderByElements()`, alias-resolved the same way WHERE columns already were.
2. **`CandidateGenerator`: ORDER BY-aware candidates.** A single-column sort-avoidance candidate per order-by column not already filter-covered, plus a `(filterColumn, orderByColumn)` composite per pair on the same table — lets Postgres serve the WHERE predicate and avoid a separate sort with one index. Verified live: `SELECT * FROM orders WHERE customer_id = 500 ORDER BY created_at;` proposed all 3 shapes (`(customer_id)` 97.9%, `(created_at)` alone 0.0%, `(customer_id, created_at)` 99.0% — the composite correctly won).
3. **`CandidateIndex.appliesTo()` extended.** Had to also match order-by-only columns, not just filter columns — otherwise `WorkloadIndexEvaluator` would silently never evaluate a pure sort-avoidance candidate against the very query that motivated it. Caught while writing the ORDER BY feature, before it could become a silent workload-level gap — not found live.
4. **New `ExistingIndexManager`/`ExistingIndex`** (`advisor.candidates`). Reads `pg_indexes`, parses `indexdef` (controlled string search — Postgres always schema-qualifies the table name, and every index this project creates is a plain column list, never functional/partial), prefix-matches candidates against real indexes. Wired into `QueryAdvisor` before evaluation, so a covered candidate never pays the real `CREATE`/`DROP INDEX` cost of being evaluated. Verified live: `SELECT * FROM orders WHERE id = 5;` (already the primary key) now returns zero candidates, not a redundant recommendation.
5. **`RecommendationEngine.pruneDominated()`.** Drops any evaluated candidate strictly dominated by a same-or-shorter prefix candidate with equal-or-better measured improvement — a 3-column composite that helps no more than its own 1-column prefix already does is pure extra write/storage cost. Verified with a case where a composite ties its prefix (dropped) and a case where it genuinely beats it (both kept, matching the real `(customer_id, created_at)` result above).
6. **New `AiExplanationService`** (`advisor.ai`), calling Gemini via `java.net.http.HttpClient` + Jackson (no new dependency needed — both already present). Reads `GEMINI_API_KEY` from the environment; degrades gracefully to a clear fallback message on a missing key, network failure, timeout, or malformed response — verified live with no key set: `{"reasoning": ["Gemini API key not found."]}`, not an exception.
7. **New `AnalyzeResponse` DTO** (`advisor.web`), built via `QueryAdvisor.analyzeDetailed()` (new method exposing the full evaluated-candidate list and parsed query, not just the filtered recommendations `analyze()` still returns for backward compatibility). Deliberately surfaces real measured execution time in milliseconds as `originalCost`/`bestCost`/candidate `cost` — not a planner cost estimate — since that's the actual point of this engine versus the alternative. Frontend labels updated to match (`"Original Cost"` → `"Original Time (ms)"`, chart title/tooltip likewise) so the UI doesn't claim to show something it isn't.
8. **New `/generate-explanation` endpoint**, wired to `AiExplanationService`.
9. **Frontend rewiring** (`index-advisor/src/pages/SingleQuery.jsx` + label text in `ResultCard.jsx`/`CandidateTable.jsx`/`CostComparisonChart.jsx`): axios calls moved from the C++ backend's `/api/analyze-query`/`/api/generate-explanation` to Java's `/analyze`/`/generate-explanation`; request/response field names updated to match. `npm run build` verified clean.

**End-to-end verification, against the live Spring Boot server and the real local database** (not just unit tests): started the server, `curl`'d `/analyze` with a plain filter query, an ORDER BY query, an already-PK-covered query, and a JOIN query — all returned exactly the shapes above, all matched what the frontend components expect field-for-field. `/generate-explanation` verified to degrade gracefully with no API key configured. Server stopped cleanly afterward, zero leftover experimental indexes (both new integration tests clean up via `@AfterAll`, matching the project's existing discipline).

**Test suite: 59 tests, 0 failures** (up from 51) — 9 new tests for ORDER BY parsing/candidate generation/`appliesTo`, 5 new integration tests for `ExistingIndexManager` against the real database, 3 new tests for prefix-domination pruning.

**`WorkloadOptimization.jsx` (the workload frontend page) was found still 100% mock** — never wired to either backend (no C++ workload endpoint ever existed to wire it to). Out of scope for that round's explicit ask (the contention was specifically single-query); left as a flagged next step — closed the following round, below.

### Workload Page — Live Wiring + Demo-Ready Query Building — DONE

Triggered by two direct observations: no loading spinner ever appeared while a workload was "analyzing" (because `handleAnalyze()` was fully synchronous mock code — a spinner has nothing to attach to without a real network call), and a demo presenter shouldn't need to hand-pick a workload file.

1. **`WorkloadQueryAdvisor.analyzeDetailed()`** — same pattern as single-query's `analyzeDetailed()`: exposes the full `WorkloadProfile` and every candidate's raw `List<WorkloadEvaluationResult>` (not just the final chosen `List<Recommendation>`), so the web layer can compute real per-index numbers. `analyzeWorkload()` now just calls it and returns `.recommendations()` — zero behavior change for existing callers.
2. **New `WorkloadAnalyzeResponse` DTO** — for each chosen `Recommendation`, finds its originating `WorkloadEvaluationResult` and filters `queryEvaluations` down to exactly the queries in `Recommendation.affectedQueries()` (reusing the engine's own >=10%-improvement decision rather than re-deriving that threshold), then averages `beforeExecutionTime`/`afterExecutionTime` across just those. `coverage` is `affectedQueries().size() / totalWorkloadQueries * 100` — a real fraction of the actual workload, not of the candidate's own applicable-query subset. Workload-wide `beforeCost`/`afterCost`/`improvement` are averaged across the chosen indexes. `/analyze-workload`'s response shape changed from raw `List<Recommendation>` to this DTO — safe to change since nothing else depended on the old shape yet.
3. **5 new tests** (`WorkloadAnalyzeResponseTest`) — cost/improvement correctly reflect only the "helped" subset (not diluted by a barely-positive query that didn't clear the threshold), coverage is workload-wide not candidate-local, overall before/after/improvement average correctly across multiple chosen indexes, empty-recommendation and orphaned-candidate (defensive) cases handled without throwing. **64 tests total, 0 failures.**
4. **7 themed sample workload files** (`backend/workload/samples/*.txt`, built the prior round — 6 business-area themes at 30-34 queries each, plus a 100-query cross-table stress-test file) copied into `index-advisor/public/` so the frontend can fetch them as static assets — no new backend endpoint needed for what's fundamentally static demo content.
5. **`WorkloadOptimization.jsx` rebuilt** around three query-source modes instead of a single mandatory file upload: a **preset dropdown** (fetches and parses one of the 7 themed files via a JS port of `WorkloadReader.readQueries()` — strip `--` comments, split on `;`, identical semantics to the backend), **write-your-own** (a textarea, same parsing), and **quick-add** (three curated categories — Customers, Products, Purchases — each a row of one-click chips that append a specific, already-schema-verified query). All three modes converge into one visible, editable "queries in this workload" list (removable per-item, clearable) that actually gets sent to `/analyze-workload` — consistent UX regardless of how the workload was built.
6. **Loading spinner wired to the real call** — `AnalyzeButton` already had full spinner support built in (`loading` prop, `.button-spinner` CSS) from the single-query page; the workload page just never passed `loading` or tracked async state, because there was no async call to track. Fixed directly: `handleAnalyze` is now a real `axios.post` to `/analyze-workload` with `loading` state driving both the button's own spinner and a separate loading card explaining *why* larger workloads take longer (every candidate is a real CREATE INDEX / measure / DROP cycle).
7. **Result rendering reuses the existing components unchanged** (`WorkloadResultCard`, `CostComparisonChart`, `CandidateTable`, `CoverageAnalysisCard`, `RecommendedIndexSetCard`) — the new `WorkloadAnalyzeResponse` shape was deliberately designed to match what they already expected, so only label text needed fixing (`WorkloadResultCard`'s "Original/Optimized Cost" → "Avg. Original/Optimized Time (ms)", same real-measured-time accuracy fix already applied to the single-query page).

**Verified end to end against the live server**, not assumed from the code: `/analyze-workload` hit directly with a real 30-query preset file and a real `Origin` header — real per-index costs, real coverage percentages matching `helped-queries / 30`, correct `Access-Control-Allow-Origin` on the actual response. Frontend confirmed serving preset files correctly via `curl` against the Vite dev server. `npm run build` and the full 64-test backend suite both clean.

**Follow-up fix, same round: quick-add and custom-text queries now append rather than silently dedupe.** The first version's `addQuickQuery`/`addCustomText` both skipped adding a query already present in the list — reasonable-sounding, but wrong for the actual use case: a presenter should be able to click one quick-add chip repeatedly to build up a large workload (e.g. toward 100 queries) without needing that many distinct pre-written buttons. Both functions now plain-append; React's index-based `key`/removal already made this safe (no value-based key collision risk from allowing duplicates).

### Deployment — Docker + Render (backend) + Vercel (frontend) — DONE

Off-roadmap, but the single highest-leverage remaining item per the prior round's assessment (see `RUNDOWN.html`'s Deployment/Ops group). Docker first: a multi-stage `backend/Dockerfile` (Maven build stage → JRE-only runtime stage) and `index-advisor/Dockerfile` (Node build stage → static bundle served by Nginx, `index-advisor/nginx.conf`), tied together with a root `docker-compose.yml` for one-command local startup. Postgres is deliberately not one of the containers — both the canonical and sandbox databases are already Supabase-hosted (see Milestone 0's two-tier architecture), so there's nothing local to containerize there.

**A real bug found and fixed getting the backend live on Render, not just "it deployed":** the build succeeded, but the service never went healthy. Root cause: Render's Docker-based web services must bind to whatever port Render assigns at runtime via the `PORT` environment variable — it's dynamic per-instance, not fixed — and `application.properties` never set `server.port`, so Spring Boot defaulted to 8080 while Render's health check probed a different port entirely. Fixed with `server.port=${PORT:8080}` — the `:8080` fallback keeps local dev and `docker-compose up` completely unaffected.

**Verified end to end after the fix, not just "build succeeded, moving on":**
- A real `curl -X POST https://index-advisor.onrender.com/analyze` returned a genuine measured recommendation (`orders(customer_id)`, 96.5% improvement) — the live backend actually talking to the real database, not a health-check stub.
- A CORS preflight (`OPTIONS /analyze` with `Origin: https://index-advisor.vercel.app`) returned `200` with the correct `Access-Control-Allow-Origin` — `CORS_ALLOWED_ORIGIN` was already correctly set on Render.
- Rather than trust that Vercel's `VITE_API_URL` build-time env var was actually set correctly, the deployed frontend's built JS bundle was fetched directly and grepped for its API calls — confirmed all three endpoints (`/analyze`, `/analyze-workload`, `/generate-explanation`) point at the live Render URL, not `localhost`.

**Known, disclosed limitation:** Render's free tier spins the backend down after inactivity, adding up to ~50 seconds to the first request afterward (visible directly in Render's own dashboard warning) — a real cost of the free tier, not a bug, and not hidden from anyone demoing this live.

**Still open:** no CI/CD. Both Render and Vercel auto-deploy on push at the platform level, but nothing runs the 94-test suite as a gate first — a broken push can still reach the live URL.

### Local Dev Sync (post-merge) — DONE

The Deployment round above landed on a teammate's `consolidated` branch. Pulling it into this branch was a plain fast-forward merge, but merging code across two people's local setups is exactly the kind of change that should be verified by running the real thing, not assumed clean from reading the diff — and it wasn't clean.

**A real regression, caught by `mvn test`, not by reading the diff:** the merged commits renamed the sandbox database's expected name from `index_advisor` to `ecom_test` inside `IndexEvaluator`/`HypoIndexEvaluator`'s safety checks, but `application.properties`'s default, `Main.java`, `WorkloadBenchmark.java`, four integration test files' hardcoded JDBC URLs, and the actual local Postgres database itself were never part of that rename. Result: 94 tests → 1 failure + 6 errors, every error the identical `IllegalStateException: Refusing to run index experiments against database 'index_advisor'... only runs on the 'ecom_test' sandbox copy`. Presented three fix options (rename the local DB, point local dev at a Supabase-hosted `ecom_test`, or leave it to the user); **renaming the local DB was chosen.**

**Fixed:** confirmed zero active connections via `pg_stat_activity`, then `ALTER DATABASE index_advisor RENAME TO ecom_test;`. Updated `application.properties`'s `db.name` default, `Main.java`, `WorkloadBenchmark.java`'s hardcoded JDBC URL, and all four integration test files' JDBC URLs/doc comments to match — plus one stale test assertion string (`IndexEvaluatorIntegrationTest` still expected the old `'index_advisor'` wording in a thrown-message check). Re-ran: **94/94 passing.**

**A second, related gap found and fixed:** the merge also switched `SingleQuery.jsx`/`WorkloadOptimization.jsx` from hardcoded `localhost:8080` URLs to `import.meta.env.VITE_API_URL` (needed for the Vercel deployment), but no local `.env`/`.env.local` file existed for the frontend — every local API call would have resolved to `undefined/analyze`. Fixed by creating `index-advisor/.env.local` (`VITE_API_URL=http://localhost:8080`) — confirmed gitignored via `git check-ignore -v` (matched by the existing `*.local` rule), so it can't be accidentally committed. `npm run build` still clean.

**Two real, disclosed gaps found in the new Docker config, not yet fixed** (left for a deliberate decision rather than silently patched, since it's a teammate's newly-committed config and the right fix might depend on how they want Docker vs. Vercel to differ):
- `docker-compose.yml` references `env_file: - backend/.env`, which doesn't exist in the repo (correctly gitignored, since it would hold secrets) — `docker-compose up` won't run out of the box without first creating one locally.
- `index-advisor/Dockerfile` never receives `VITE_API_URL` as a build `ARG`, so a Docker-built frontend image would bake in `undefined` for the API URL. Vercel's own build pipeline sets `VITE_API_URL` independently at build time and is unaffected — this is specifically a `docker-compose`/local-Docker-build gap, not a live-deployment one.

### CI Pipeline — DONE (CI only — not wired to gate deployment yet, see below)

GitHub Actions (`.github/workflows/ci.yml`), triggered on every push/PR to `main`/`consolidated`. Two jobs, running in parallel:

- **Backend:** builds a Postgres 17 + HypoPG image from a checked-in Dockerfile (`.github/docker/postgres-ci.Dockerfile` — hypopg isn't in the stock `postgres` image, and GitHub Actions service containers can only reference a pre-built registry image, not a local Dockerfile, so this is built as an explicit workflow step instead), starts it, creates a Postgres role matching the runner's own OS user (`$(whoami)`) so the test suite's hardcoded `jdbc:postgresql://localhost:5432/ecom_test` + OS-username + no-password connection works completely unmodified, restores schema, regenerates the real 10,000-row dataset via this project's own `npm run populate:all`, runs the existing 18-point `npm run verify` integrity check, then runs the full `mvn test` suite.
- **Frontend:** `npm ci && npm run build`.

**A real reproducibility gap found and fixed as a side effect, not the original goal:** there was no versioned schema anywhere in the repo — the only record of table structure lived inside `database/ecom_dump_*.sql`, which is (correctly) gitignored as generated data. Extracted a schema-only `database/schema.sql` via `pg_dump --schema-only` and added a `.gitignore` exception (`!database/schema.sql`) so it's the one dump-family file that *is* versioned. CI restores this schema, then regenerates data through the real populate pipeline rather than committing a multi-MB data dump to git.

**Verified by actually running the recipe locally before writing the workflow, not written blind:** built the custom Postgres+HypoPG image, ran it in Docker, created the matching role, restored `schema.sql` (found and fixed one real bug in this step — a fresh `pg_dump --schema-only` omitted `CREATE SCHEMA public;` that the original full dump had included, so the restore step now creates the schema explicitly rather than depending on the dump to do it), ran `npm run populate:all` end to end (produced 23,848 order_items — matching the ~23,600–23,900 range already documented from real runs), ran `npm run verify` (**18/18 checks passed**), and confirmed the exact two HypoPG functions this project's code calls (`hypopg_create_index`, `hypopg_reset`) both work against the freshly-installed extension.

**Disclosed, accepted trade-off:** CI regenerates data fresh each run instead of restoring a fixed snapshot, so there's a small amount of run-to-run non-determinism the local dev database (populated once, reused forever) doesn't have. Risk is low in practice — verified while building this that category assignment is dense enough (near 1:1 categories-to-products) that any given category is highly selective by construction, which is exactly the property the suite's assertions depend on — but it's not literally zero, unlike testing against one fixed database.

**What this is not, stated plainly rather than implied:** this is CI, not CD. It gives every push a real, visible pass/fail signal (a real Postgres + HypoPG run, not a stub), but it does **not** control deployment — Render and Vercel are each configured through their own dashboards to auto-deploy on push independently of GitHub Actions' result, and nothing in this repo connects the two. A push that fails CI can still deploy successfully to both live URLs; a push that passes CI still deploys automatically regardless. Closing that gap (disabling each platform's git-auto-deploy in favor of a deploy hook triggered only after CI passes) is real, separate work, not yet done — tracked as its own item below rather than folded silently into "CI/CD."

### Write Overhead Benchmark — DONE, honestly inconclusive at current scale

`backend/src/main/java/advisor/WriteOverheadBenchmark.java` — a standalone benchmark (same pattern as `WorkloadBenchmark`), measuring real INSERT/UPDATE/DELETE cost of `products(category_id)` before/after the index exists. Full writeup: `SCALABILITY_BENCHMARK.md`.

**Two real measurement biases found and fixed while building it** — both the same class of bug as `IndexEvaluator.measureStable()`'s already-documented cache-warming fix: (1) `CREATE INDEX`'s own full-table scan warms the page cache asymmetrically between the baseline and indexed measurement phases; (2) JVM JIT compilation reaching steady state only after many invocations meant whichever phase ran second always looked faster, independent of the index. Both fixed (an equivalent warm-up scan before baseline; a 300-iteration JIT warm-up before either phase).

**Honest result, not a clean win:** even after both fixes, real per-statement write cost at this project's 10,000-row scale sits inside the noise floor of client-side JDBC timing — 5 trials of INSERT overhead ranged from -40.3% to +43.9%, sign flipping trial to trial. Aggregate across 5 trials: INSERT -10.3%, UPDATE +4.4%, DELETE +1.9% — all small, none trustworthy as a precise number. The honest, disclosed conclusion: a single narrow index's write-maintenance cost is genuinely small relative to this table's size, not a meaningful trade-off against the 90%+ read-side gains measured throughout this project — but it can't yet be wired into `Recommendation.tradeoffs` as a real per-recommendation number, since the current method can't resolve it cleanly. Directly motivates the new "proper scalability experiment" item below.

### Not Yet Started
Milestone 16's write-overhead measurement now exists (above) but isn't clean enough to wire into live `Recommendation.tradeoffs` yet. Off-roadmap, newly added: a proper scalability experiment across row counts (not just this project's fixed 10,000), and real-world dataset validation — see Future Improvements, below.

---

## Priority Improvements (post-Milestone-14, off the original roadmap)

Five items, done in this order deliberately: safe/contained changes first, a test suite third (so the two riskiest remaining changes — HypoPG and JOIN support — had a regression safety net), highest-risk last. Every item found and fixed at least one real bug during verification, not just "implemented, looks right."

### 1. Quantitative storage cost — DONE
`IndexEvaluator` measures `pg_relation_size()` on the experimental index while it still exists (between `CREATE` and `DROP`) — a real number, not an estimate. Threaded through a new `EvaluationResult.indexSizeBytes` field and a new `Recommendation.indexSizeBytes` field; `Recommendation.tradeoffs` now reads `"Additional index storage: 208.0 KB"` instead of the old generic string. Write-overhead measurement is still out of scope (would need a real write benchmark) — `tradeoffs`' second line now says `"(not measured)"` explicitly rather than implying it was.

### 2. HikariCP connection pooling — DONE, with a real concurrency bug found and fixed
`PostgresManager` now owns a `HikariDataSource` instead of calling `DriverManager.getConnection()` fresh per call. Public constructor signature unchanged, so no caller needed to change. Added `close()` (`AutoCloseable`), wired to `Main.java` (explicit calls) and `AdvisorConfig`'s `@Bean(destroyMethod = "close")`.

**Bug found via concurrent-load testing, not by inspection:** sent 5 identical concurrent `/analyze` requests — 4 came back `500`. Root cause: `IndexEvaluator` built a *deterministic* index name from table+columns (`idx_experiment_orders_customer_id`), so two concurrent requests evaluating the same candidate raced on `CREATE INDEX` — one won, the rest failed with "relation already exists." This bug existed structurally since Milestone 10; concurrent load simply wasn't achievable before pooling removed per-call connection latency. Fixed by appending a per-call `AtomicLong` counter to every index name (`idx_experiment_orders_customer_id_47`). Re-tested: 5/5 concurrent identical requests now succeed.

### 3. JUnit test suite — DONE (42 tests, 0 failures)
Added `spring-boot-starter-test` (JUnit 5 + AssertJ + Mockito, test-scoped). Split deliberately: fast pure-unit tests for everything that doesn't need a database, one real integration test class for what does (this project's whole ethos is measuring against the real thing, not mocking).

```
SqlParserTest                    10 tests — parsing, including the ParenthesedExpressionList regression and 4 JOIN cases
CandidateGeneratorTest            7 tests — including 3 JOIN-candidate cases
CandidateIndexTest                8 tests — appliesTo(), including 3 JOIN-column cases
RecommendationEngineTest          4 tests — threshold filtering, ranking, impact tiers
WorkloadRecommendationEngineTest  4 tests — encodes the real Milestone 13 overlap-avoidance scenario with real measured numbers
IndexEvaluatorLogicTest           6 tests — the pure, extracted computeImprovement() logic, including both real bugs as regression tests
IndexEvaluatorIntegrationTest     3 tests — real database: safety check, schema rejection, real measure-and-cleanup
```
`IndexEvaluator.evaluate()`'s improvement/clamp math was extracted into a package-private static `computeImprovement(QueryPlan, QueryPlan)` specifically to make it unit-testable without a database — this is what let both the cache-warming bug (Priority Improvement from the previous session) and the JOIN-clamp bug (item 5 below) become fast, permanent regression tests instead of one-off manual verifications.

### 4. HypoPG — DONE, cross-validated against real measurements
Installed via Homebrew (`brew install hypopg`, official homebrew-core formula, stable/bottled), enabled on the local database (`CREATE EXTENSION hypopg;`). New `HypoIndexEvaluator`: creates a *hypothetical* index (`hypopg_create_index()` — no real storage, no lock, microseconds not milliseconds), asks the planner via plain `EXPLAIN` (no `ANALYZE` — a hypothetical index is invisible to real execution) whether it would help, resets.

**A real architectural issue found and fixed before this could work at all:** HypoPG's hypothetical indexes are session-local (per physical connection) state — invisible from any other connection, including a different one borrowed from the same HikariCP pool. `PostgresManager`'s existing methods each independently borrow a (possibly different) connection per call, which is fine for real, persisted database state but would silently break HypoPG usage — a hypothetical index created in one call might be invisible to the very next call. Fixed by adding `PostgresManager.openSession()` / `ConnectionSession`, pinning one physical connection for the whole create→explain→reset sequence.

**Cross-validated against `IndexEvaluator`'s real measurements**, not just assumed correct — `Main.runHypoPgDemo()` runs both evaluators on the same 3 candidates and prints both:
```
products(category_id)          HypoPG: Seq Scan -> Bitmap Heap Scan  cost 422.00 -> 11.57  (97.3% est.)
                                Real:   Seq Scan -> Bitmap Heap Scan  time 0.635ms -> 0.006ms (99.1% measured)
products(status)                HypoPG: Seq Scan -> Seq Scan          cost 422.00 -> 422.00 (0.0% est.)
                                Real:   Seq Scan -> Seq Scan          time 0.637ms -> 0.638ms (-0.2% measured)
products(category_id, status)   HypoPG: Seq Scan -> Index Scan        cost 422.00 -> 8.05   (98.1% est.)
                                Real:   Seq Scan -> Index Scan        time 0.638ms -> 0.005ms (99.2% measured)
```
All 3 agree on plan shape and qualitative outcome, including correctly predicting **zero** benefit for `products(status)`. Scope boundary, stated plainly: this is a demonstrated, working, additional capability, cross-validated for trustworthiness — not (yet) wired into `WorkloadQueryAdvisor`'s actual selection pipeline as a screen-then-validate two-phase process. At the current ~9-candidate scale, `IndexEvaluator`'s real DDL is still fast enough that this wasn't the bottleneck; the value delivered here is a proven, ready-to-wire mechanism plus the connection-pinning fix that makes it safe to use, not a scale problem that needed solving yet.

### 5. JOIN support — DONE, with a real correctness bug found and fixed
Extended `SqlParser`/`ParsedQuery`/`CandidateGenerator`/`CandidateIndex` to parse simple two-table equi-joins and attribute WHERE columns to their real table (alias-resolved), not just the primary `FROM` table.

**New types:** `TableColumn(table, column)`, `JoinClause(leftTable, leftColumn, rightTable, rightColumn)`. **`ParsedQuery`** redesigned as `(tableName, qualifiedFilterColumns: List<TableColumn>, joins: List<JoinClause>)` — but `tableName()` and `filterColumns()` (now a *derived* method, not a stored field) keep returning exactly what they did before for single-table queries, so every pre-existing caller (`CandidateGenerator`'s filter-candidate logic, `WorkloadAdvisor`'s frequency counting, all pre-JOIN tests) kept working **completely unchanged** — verified by re-running the full pre-existing test suite and CLI demo with zero regressions. Added `ParsedQuery.singleTable(table, columns)` factory for the common case.

**`SqlParser`** builds an alias→real-table map from the `FROM` item and every `JOIN`'s right-hand item (verified against the actual JSqlParser 5.3 jar via `javap` before writing code — same discipline as the Milestone 5 `ParenthesedExpressionList` investigation — confirming `Column.getTableName()` returns the qualifier text, or `null` when unqualified). Only simple single-condition equi-joins are recognized (`ON left.col = right.col`); multi-condition `ON` clauses, non-equality joins, and joins against non-table `FromItem`s (e.g. subqueries) are a stated scope boundary — skipped, not guessed at.

**`CandidateGenerator.generate()`** now proposes: filter-column candidates per table involved (not just the primary one), plus one single-column candidate per side of every join clause — indexing join columns is the classic use case JOIN support exists to cover. **`CandidateIndex.appliesTo()`** extended to also match single-column candidates against either side of a join clause, not just WHERE-filtered columns — needed for `WorkloadIndexEvaluator` to know which queries a join-column candidate should be evaluated against.

**Real, live-verified results** — two join queries against the actual database:
```
orders JOIN customers ON o.customer_id = c.id WHERE c.status = 'active'
  → No recommendation. Verified by hand via EXPLAIN ANALYZE: Hash Join, orders fully
    scanned regardless (no selective filter of its own), customers.status matches ~59%
    of rows (not selective). A genuinely correct negative result, not a bug.

customers JOIN orders ON c.id = o.customer_id WHERE c.id = 500
  → orders(customer_id) recommended, HIGH, 98.9% improvement. Nested Loop plan:
    customers.id=500 uses the existing PK index (highly selective outer), but orders had
    no index on customer_id, so the inner side Seq Scanned all 10,000 rows per outer match.
    The textbook case join-column indexing exists for.
```

**A second real bug found via this exact live test, not by inspection:** the second query's recommendation was initially **missing** — `computeImprovement`'s same-node-type clamp (Priority Improvement, previous session) compared only the plan's *root* node type. For a join, the root is the join *strategy* (`Nested Loop`), which correctly stayed identical while the *nested* inner scan legitimately changed from `Seq Scan` to `Bitmap Heap Scan` — a real, ~93%-verified-by-hand improvement that the clamp was silently discarding because it only ever looked at the top of the tree. This directly undermined JOIN support's core value proposition (recommending join-column indexes) for exactly the scenario it was built to handle. Fixed by adding `QueryPlan.getAllNodeTypes()` (depth-first walk of the whole plan tree, not just the root) and comparing that instead — behavior-preserving for every single-table case (a leaf plan's `getAllNodeTypes()` is just `[rootType]`, identical to the old single-value comparison), confirmed by the full pre-existing test suite still passing unchanged. Added 2 new regression tests encoding this exact scenario with the real measured numbers (`2.438ms → 0.159ms`, `Nested Loop[Seq Scan]` vs `Nested Loop[Bitmap Heap Scan]`).

Known, stated cosmetic gap (not a correctness issue — the number and the recommendation decision are both now right): `Recommendation.observedEffect` for this query still displays `"Nested Loop -> Nested Loop"`, which is technically true but doesn't show *what* actually changed. Making the displayed description reflect the nested change too is a real, separate, minor polish item — logged as a Future Improvement rather than folded into this fix, to keep the fix itself minimal and the bug report honest about what was and wasn't addressed.

Zero leftover real indexes and no test failures throughout all five items — checked after every single change, not just at the end.

---

## Actual Current Package Structure

```
backend/
├── pom.xml                                          spring-boot-starter-parent 3.5.3, spring-boot-starter-web,
│                                                     spring-boot-starter-test (test scope), HikariCP (managed),
│                                                     postgresql (managed, 42.7.7), jsqlparser 5.3 (pinned)
├── workload/
│   └── queries.sql                                  10 hand-written queries against the real schema
└── src/
    ├── main/
    │   ├── resources/application.properties           externalized DB config, env-var-backed with defaults
    │   └── java/advisor/
    │       ├── Main.java                              CLI demo/verification entry point (not a test suite)
    │       ├── ApiLimits.java                          MAX_WORKLOAD_QUERIES/MAX_CANDIDATES/MAX_SQL_LENGTH — shared
    │       │                                            constants, checked before any real DB work runs
    │       ├── WorkloadBenchmark.java                   standalone entry point (not JUnit) — screened vs. real-only
    │       │                                            pipeline timing across sample workloads; see
    │       │                                            docs/SCALABILITY_BENCHMARK.md
    │       ├── WriteOverheadBenchmark.java               standalone entry point (not JUnit) — real INSERT/UPDATE/
    │       │                                            DELETE cost of an index; see docs/SCALABILITY_BENCHMARK.md
    │       ├── QueryAdvisor.java                       single-query orchestrator (Milestone 12); analyzeDetailed()
    │       │                                            exposes parsed query + all results, analyze() wraps it;
    │       │                                            validateSql() rejects empty/oversized queries fast
    │       ├── WorkloadQueryAdvisor.java                workload orchestrator (Milestone 13); generate candidates ->
    │       │                                            screen with HypoPG -> validate promoted ones for real ->
    │       │                                            choose index set; validateWorkload()/validateCandidateCount()
    │       │                                            reject oversized requests before real DB work runs
    │       ├── ai/
    │       │   └── AiExplanationService.java             Gemini call via java.net.http.HttpClient; graceful fallback
    │       ├── web/
    │       │   ├── IndexAdvisorApplication.java         @SpringBootApplication entry point (Milestone 14)
    │       │   ├── AdvisorConfig.java                    PostgresManager bean, reads application.properties
    │       │   ├── AdvisorController.java                POST /analyze, POST /analyze-workload,
    │       │   │                                          POST /generate-explanation, error handling
    │       │   ├── AnalyzeRequest.java                   record: query
    │       │   ├── AnalyzeResponse.java                   record: bestIndex/originalCost/bestCost/improvement/
    │       │   │                                          candidates/analysis — built from analyzeDetailed()
    │       │   ├── AnalyzeWorkloadRequest.java            record: queries
    │       │   ├── GenerateExplanationRequest.java         record: query, bestIndex, improvementPercent
    │       │   ├── GenerateExplanationResponse.java        record: reasoning
    │       │   └── ErrorResponse.java                     record: error
    │       ├── database/
    │       │   └── PostgresManager.java                  HikariCP-pooled; executeScalar/executeColumn/explainAnalyze/
    │       │                                              executeUpdate; openSession()/ConnectionSession for
    │       │                                              same-connection sequences (HypoPG)
    │       ├── parsing/
    │       │   ├── ParsedQuery.java                       record: tableName, qualifiedFilterColumns, joins,
    │       │   │                                          qualifiedOrderByColumns (+ filterColumns() derived,
    │       │   │                                          singleTable() factory)
    │       │   ├── TableColumn.java                       record: table, column
    │       │   ├── JoinClause.java                        record: leftTable, leftColumn, rightTable, rightColumn
    │       │   └── SqlParser.java                         SQL text -> ParsedQuery (JSqlParser, alias-aware,
    │       │                                               JOIN-aware, ORDER BY-aware)
    │       ├── planning/
    │       │   └── QueryPlan.java                         EXPLAIN JSON -> typed accessors + getAllNodeTypes()
    │       │                                              (depth-first full plan shape, not just root) +
    │       │                                              describeChange() — diffs before/after node-type
    │       │                                              sequences, describing nested changes a root-only
    │       │                                              comparison would misreport as "no change"
    │       ├── candidates/
    │       │   ├── CandidateIndex.java                    record: tableName, columns (+ appliesTo — filter-,
    │       │   │                                          join-, and order-by-column aware — toDdlColumnList)
    │       │   ├── CandidateGenerator.java                 ParsedQuery -> List<CandidateIndex>, per-table filter
    │       │   │                                           candidates + per-side join candidates + ORDER BY-aware
    │       │   │                                           sort-avoidance singles/composites
    │       │   ├── ExistingIndex.java                      record: tableName, columns (from a real pg_indexes row)
    │       │   └── ExistingIndexManager.java                reads pg_indexes, prefix-matches candidates against
    │       │                                                real indexes to skip redundant evaluation
    │       ├── evaluation/
    │       │   ├── EvaluationResult.java                   record: + indexSizeBytes, observedEffect (full plan-shape
    │       │   │                                            diff, computed once via QueryPlan.describeChange)
    │       │   ├── HypoEvaluationResult.java                record: HypoPG screening result (estimated cost only)
    │       │   ├── IndexEvaluator.java                      CREATE/measure/DROP, DB safety guard, schema allowlist,
    │       │   │                                            measureStable, unique index names, computeImprovement
    │       │   │                                            (full plan-shape clamp), pg_relation_size
    │       │   └── HypoIndexEvaluator.java                  hypopg_create_index/EXPLAIN/hypopg_reset; evaluate()
    │       │                                                overload accepts a caller-provided ConnectionSession
    │       │                                                so batch screening reuses one session, not one per call
    │       ├── workload/
    │       │   ├── WorkloadReader.java                      queries.sql -> List<String>
    │       │   ├── WorkloadQuery.java                       record: sql + ParsedQuery + QueryPlan
    │       │   ├── WorkloadProfile.java                     record: List<WorkloadQuery> + column frequency map
    │       │   ├── WorkloadAdvisor.java                      List<String> -> WorkloadProfile (profiling only)
    │       │   ├── WorkloadHypoScreener.java                 candidate x workload -> promoted candidates only,
    │       │   │                                             via HypoPG estimates (10% threshold)
    │       │   ├── QueryEvaluation.java                      record: sql + EvaluationResult pairing
    │       │   ├── WorkloadEvaluationResult.java             record: candidate + all its QueryEvaluations
    │       │   ├── WorkloadIndexEvaluator.java               candidate x workload -> WorkloadEvaluationResult
    │       │   ├── IndexSetBudget.java                       record: maxStorageBytes/maxIndexCount; UNLIMITED/
    │       │   │                                             storageBytes(n)/indexCount(n) factories
    │       │   └── WorkloadRecommendationEngine.java          greedy index SET selection + budget-aware overload
    │       └── recommendation/
    │           ├── Recommendation.java                       record: + indexSizeBytes, formatBytes()
    │           └── RecommendationEngine.java                  filter + rank + tier + pruneDominated()
    └── test/java/advisor/
        ├── QueryAdvisorValidationTest.java                    5 tests
        ├── WorkloadQueryAdvisorValidationTest.java             9 tests
        ├── WorkloadQueryAdvisorIntegrationTest.java            2 tests (real database)
        ├── parsing/SqlParserTest.java                        14 tests
        ├── planning/QueryPlanTest.java                         6 tests
        ├── candidates/CandidateGeneratorTest.java              10 tests
        ├── candidates/CandidateIndexTest.java                  10 tests
        ├── candidates/ExistingIndexManagerIntegrationTest.java  5 tests (real database)
        ├── recommendation/RecommendationEngineTest.java        7 tests
        ├── workload/WorkloadRecommendationEngineTest.java      9 tests
        ├── workload/WorkloadHypoScreenerIntegrationTest.java   3 tests (real database)
        ├── web/WorkloadAnalyzeResponseTest.java                5 tests
        ├── evaluation/IndexEvaluatorLogicTest.java             6 tests
        └── evaluation/IndexEvaluatorIntegrationTest.java       3 tests (real database)
```

44 main Java files (re-counted directly via `find`, not carried over from an earlier round — the prior "39" here had already drifted), 14 test files (94 tests, 0 failures), 1 `pom.xml`, 1 `application.properties`, 1 workload file. Verified via `mvn test` (fast, repeatable), `mvn exec:java` (full CLI demo against the real database), `mvn exec:java -Dexec.mainClass=advisor.WorkloadBenchmark` (scalability benchmark) / `-Dexec.mainClass=advisor.WriteOverheadBenchmark` (write-overhead benchmark — the `exec.mainClass` property, previously hardcoded, is now overridable), and live `curl` against a real running server. A GitHub Actions pipeline (`.github/workflows/ci.yml`) now runs the same `mvn test` against a real Postgres + HypoPG on every push/PR, plus the frontend build.

```
index-advisor/                                        React (Vite) frontend, pulled from the repo's main branch
├── src/pages/
│   ├── SingleQuery.jsx                                wired to Java's /analyze + /generate-explanation
│   └── WorkloadOptimization.jsx                        live -- preset/custom/quick-add query building, wired to /analyze-workload
└── src/components/                                     ResultCard, CandidateTable, QueryAnalysisCard,
                                                          CostComparisonChart, Recommendation, WorkloadResultCard,
                                                          CoverageAnalysisCard, RecommendedIndexSetCard, etc.
```

---

## Known Limitations

Organized by category. Each is a deliberate scope boundary or accepted trade-off — nothing here is a surprise, all flagged proactively.

### SQL parsing and understanding
- **~~Single-table queries only~~ — JOIN support added.** Simple two-table equi-joins (`ON left.col = right.col`) parse correctly, alias-resolved, with WHERE columns attributed to their real table. **Still limited:** only single-condition equi-joins recognized — multi-condition `ON` clauses, non-equality joins, 3+ table chains, and joins against non-table `FromItem`s (subqueries) are a stated scope boundary, skipped rather than guessed at.
- **`JSqlParser`, not Postgres's real grammar** (ANTLR-based). Bounded/mitigated, not eliminated.
- **~~No `ORDER BY` awareness~~ — added.** Table-attributed `qualifiedOrderByColumns`, alias-resolved the same way WHERE columns are. Still no subquery/CTE/`GROUP BY`/`HAVING` awareness — only WHERE-clause predicates, join columns, and now ORDER BY columns contribute to candidates.

### Candidate generation
- **Not the full power set** — one candidate per filter column + one full composite, per table (plus, now, ORDER BY-aware singles and filter+sort composites). Partial composites and alternate orderings never generated.
- **Composite column order is alphabetical** for filter-only composites, not selectivity-based. (Filter+ORDER BY composites are the one place column order is intentional — filter column leading, sort column trailing — since that's what actually lets Postgres serve both from one index.)
- **No expression indexes, `INCLUDE` (covering) indexes, or partial indexes.**
- **~~`appliesTo()` subset check for filters, exact-match for join columns~~ — extended to ORDER BY columns too**, still not full leftmost-prefix modelling.
- **~~No existing-index awareness~~ — added.** `ExistingIndexManager` skips any candidate already covered (exact match or leading-prefix match) by a real index in `pg_indexes`, before it's ever evaluated.
- **~~No redundant-candidate pruning~~ — added.** `RecommendationEngine.pruneDominated()` drops a candidate strictly dominated by a same-or-shorter prefix with equal-or-better measured improvement.

### Evaluation
- **~~Real DDL doesn't scale~~ — HypoPG wired into the live selection pipeline.** `WorkloadHypoScreener` screens every candidate via HypoPG first; only promoted candidates pay the real CREATE/DROP INDEX cost. Genuinely load-bearing now, not a standalone demo — and honestly benchmarked, not just claimed: ~1.2x average speedup, ranging from a 10% slowdown (ID-heavy point-lookup workloads, where screening filters almost nothing) to 46%, correlating with promotion ratio rather than workload size. See `SCALABILITY_BENCHMARK.md`.
- **~~Single-sample timing~~ — fixed, twice.** `measureStable()` (median of 3 + warm-up) plus a same-*full-plan-shape* clamp (not just root node type — that was itself a bug, found via JOIN testing, see Priority Improvements). Verified: the live-observed ~40% cache-warming artifact now measures ~-5.6%, matching the original pre-bug finding; a genuine ~93% join-column improvement that the root-only clamp had been silently discarding is now correctly credited. Residual: still single-machine, single-point-in-time; still no concurrent-load simulation.
- **~~No write-path cost measurement~~ — measured, honestly inconclusive.** `WriteOverheadBenchmark` found real per-statement INSERT/UPDATE/DELETE overhead sits inside the noise floor of client-side timing at this project's current 10,000-row scale (see Write Overhead Benchmark, above). `Recommendation.tradeoffs`' second line still says `"not measured"` — deliberately not wired to a number that isn't clean enough to show a user yet. A proper scale-up experiment (Future Improvements, below) is what would actually resolve this.
- **~~`Recommendation.observedEffect` can be misleadingly uninformative for join queries~~ — fixed.** New `QueryPlan.describeChange()` diffs the full before/after node-type sequence, not just the root; for the exact scenario that originally exposed this (`"Nested Loop -> Nested Loop"` when the real change was a nested `Seq Scan -> Index Scan`), the description now correctly says so. Re-verified live on that exact query, not just in a unit test.
- **Single machine, single point in time** — numbers are reproducible in shape, not exact figures.

### Index-set selection
- **Greedy, not globally optimal.** Index selection is structurally Set Cover/Knapsack-shaped (NP-hard); greedy marginal-utility is a standard, defensible approximation, not a guarantee.
- **~~No storage/count budget constraint~~ — added.** New `IndexSetBudget` (max storage bytes and/or max index count) is a real second stopping condition in the greedy loop, checked alongside the existing marginal-improvement threshold — not yet exposed on `AnalyzeWorkloadRequest`/the HTTP API, so it's a real engine capability without a request-level way to set it yet.
- **"Covered" is binary per query** — no modelling of a second candidate adding incremental value to an already-covered query.
- **Fixed thresholds** (`MIN_QUERY_IMPROVEMENT_PERCENT = 10.0`, `MIN_MARGINAL_SCORE = 10.0`, impact cutoffs at 50/20) — hardcoded, not tuned beyond this project's own 10 queries, not configurable without a code change.

### Data and workload realism
- 10 hand-written queries (now including 2 join queries tested via `Main.java`, not yet added to `queries.sql`/the workload-level pipeline), not derived from real traffic/logs.
- **~~Uniform distributions, artificial 1:1 order_items ratio~~ — fixed (Data Quality Improvements, above).** `order_items` now realistically 1–5 per order (~23,700 total, up from a fixed 10,000); customer and product activity now genuinely skewed (~29%/~38% top-decile concentration), verified on both local and Supabase, not just generated and assumed correct.
- **Row counts deliberately left at 10,000** (except `order_items`, which grew organically from the ratio fix). Explicit decision, not an oversight: distribution realism was judged higher-leverage than raw scale for this project's actual purpose, and increasing size is now a separable, clearly-scoped future decision rather than bundled into this one. `products.category_id` assignment is still uniform (not in scope for this pass).
- No adversarial/pathological query shapes tested.

### Engineering / production-readiness
- **~~No automated test suite~~ — fixed.** 42 JUnit tests (7 classes), 0 failures, including 1 real-database integration test class. Repeatable via `mvn test`. Both real bugs found this round (the connection-pooling race, the JOIN plan-shape clamp bug) now have permanent regression tests.
- **~~No connection pooling~~ — fixed, HikariCP.** Found and fixed a real concurrency bug in the process (deterministic index names racing under concurrent load) — see Priority Improvements.
- **~~No HTTP API~~ — fixed, Milestone 14.** `POST /analyze`, `POST /analyze-workload` live and verified.
- **~~Identifier interpolation injection risk~~ — fixed, Milestone 14.** Schema allowlist + quoted identifiers; verified with an actual crafted payload.
- **No logging framework** in application code — `System.out.println` throughout (Spring Boot's own startup/request logging via Logback exists, but isn't used by this project's own classes).
- **~~No configuration file~~ — partially fixed, Milestone 14.** `application.properties` externalizes the web path's DB config; `Main.java`'s CLI path still reads raw env vars independently — two unreconciled config paths.
- **~~No hardening against oversized/expensive requests~~ — added.** `ApiLimits` bounds query count (200), generated candidates (150), and per-query SQL length (5,000 chars) on the public API, rejected with a clean 400 before any real database work runs — verified live, an oversized request rejected in 9ms. **Still a real, disclosed gap:** this bounds request *size*, not request *duration* — a compliant-sized workload can still legitimately take real minutes (see `SCALABILITY_BENCHMARK.md`), and there's no wall-clock timeout, async cancellation, or rate-limiting yet.
- **~~Not containerized~~ — fixed.** `docker-compose.yml` + a `Dockerfile` per service; the database isn't containerized because it's already Supabase-hosted (nothing local to containerize).
- **~~Only tested locally, no deployment~~ — fixed.** Live at [index-advisor.vercel.app](https://index-advisor.vercel.app) (frontend) and [index-advisor.onrender.com](https://index-advisor.onrender.com) (backend), verified end to end — see the new Deployment section above.
- **~~No CI~~ — fixed.** GitHub Actions runs the real 94-test suite against a real Postgres + HypoPG, plus the frontend build, on every push/PR — see CI Pipeline, above. **Still a real, disclosed gap:** this is CI, not CD — a broken push still reaches both live URLs unchanged, since Render/Vercel auto-deploy independently of GitHub Actions' result. Deploy-gating is separate, not-yet-done work (see Future Improvements).
- **`docker-compose up` doesn't run out of the box.** References `env_file: backend/.env`, which is correctly gitignored but doesn't exist locally — see Local Dev Sync, above. Doesn't affect the live Render/Vercel deployment, which doesn't use `docker-compose`.
- **A Docker-built frontend image bakes in `undefined` for the API URL.** `index-advisor/Dockerfile` never receives `VITE_API_URL` as a build `ARG` — see Local Dev Sync, above. Vercel's own build (which sets it independently) is unaffected.

---

## Roadmap

```
Milestone 0   Database Foundation                          ✓ DONE
Milestone 1   Language Decision (Java)                      ✓ DONE
Milestone 2   PostgreSQL Connectivity (JDBC)                ✓ DONE
Milestone 3   Query Execution                               ✓ DONE
Milestone 4   EXPLAIN ANALYZE (JSON) Support                ✓ DONE
Milestone 5   SQL Parsing (JSqlParser → ParsedQuery)         ✓ DONE
Milestone 6   QueryPlan Representation                       ✓ DONE
Milestone 7   Workload Query Ingestion (queries.sql)          ✓ DONE
Milestone 8   Workload Profiling (frequency, cost aggregation) ✓ DONE
Milestone 9   Candidate Generation                             ✓ DONE
Milestone 10  Index Evaluation (create/measure/drop)           ✓ DONE (hardened post-hoc)
Milestone 11  Recommendation Engine                            ✓ DONE
Milestone 12  Single-Query Advisor (end-to-end)                 ✓ DONE
Milestone 13  Workload Advisor (end-to-end)                     ✓ DONE
Milestone 14  HTTP API (Spring Boot)                             ✓ DONE
Milestone 15  React Frontend Integration                          ✓ DONE (single-query and workload, both live)
Milestone 16  Index Cost / Storage / Write-Overhead Modeling       ~PARTIAL (storage budget real and used as a selection budget; write-overhead now measured but inconclusive at current scale)
```

**Completion: 97%.** Storage cost is both measured *and* used as an explicit selection budget (`IndexSetBudget`), not just reported. Write-overhead is no longer fully unstarted — `WriteOverheadBenchmark` measured it honestly and found the real signal is inside the noise floor at this project's current 10,000-row scale, not yet a number worth wiring into `Recommendation.tradeoffs`. A scale-up experiment (Future Improvements, below) is what would actually close this milestone out.

---

## Future Improvements — Prioritized

From a comparison against a ChatGPT-suggested priority list, re-ranked here by actual effort (informed by direct knowledge of this codebase) against value:

### Done
- ~~Repeated-run statistical measurement~~ — **done** (previous session).
- ~~Identifier validation before DDL~~ — **done** (Milestone 14).
- ~~Quantitative storage cost~~ — **done.** Real `pg_relation_size()`, threaded through `EvaluationResult`/`Recommendation`.
- ~~HikariCP connection pooling~~ — **done.** Found and fixed a real concurrency bug along the way (see Priority Improvements).
- ~~JUnit test suite~~ — **done.** 42 tests, 7 classes, 0 failures, one real-DB integration test.
- ~~`HypoPG`~~ — **done.** Installed, verified, cross-validated against real measurements. Found and fixed a real connection-pinning issue along the way.
- ~~JOIN support~~ — **done.** Parsing, candidate generation, live-verified against 2 real join queries. Found and fixed a real plan-shape-comparison bug along the way.
- ~~ORDER BY awareness, existing-index awareness, prefix-domination pruning, AI explanations~~ — **done** (Milestone 15, ported natively from a teammate's C++ backend after a full code-read comparison; see above).
- ~~React frontend~~ — **done, both analysis modes.** Single-query and workload pages both connected end-to-end to the Java backend, verified live.
- ~~Wire `WorkloadOptimization.jsx` to `/analyze-workload`~~ — **done**, along with a real query-building UI (preset/custom/quick-add) and the loading spinner that motivated the whole round.
- ~~Wire `HypoPG` into `WorkloadQueryAdvisor`'s actual selection pipeline~~ — **done**, and honestly benchmarked (~1.2x average, workload-dependent) rather than assumed. See `SCALABILITY_BENCHMARK.md`.
- ~~Storage/count budget as an explicit stopping condition~~ — **done**, `IndexSetBudget`. Not yet exposed on the HTTP request.
- ~~Fix `Recommendation.observedEffect` for nested plans~~ — **done**, `QueryPlan.describeChange()`, re-verified live on the original bug's exact query.
- ~~Hardening against oversized/expensive API requests~~ — **done**, `ApiLimits`. Bounds size, not duration — see the disclosed gap above.
- ~~Docker containerization~~ — **done**, `docker-compose.yml` + per-service `Dockerfile`s.
- ~~Live deployment~~ — **done.** Backend on Render, frontend on Vercel, verified end to end. Found and fixed a real bug along the way (Render's dynamic `$PORT` binding) — see the Deployment section above.
- ~~CI pipeline~~ — **done, CI only.** GitHub Actions runs the real 94-test suite against a real Postgres + HypoPG, plus the frontend build, on every push/PR. Found and fixed a real reproducibility gap along the way (no versioned schema existed anywhere — see CI Pipeline, above). **Not the same as CD** — it doesn't gate Render's/Vercel's deploys, which still trigger independently regardless of CI's result. That's the first item in "Next," below.
- ~~Write-overhead measurement~~ — **done, honestly inconclusive.** `WriteOverheadBenchmark` found the real per-statement cost is inside the noise floor of client-side timing at this project's current 10,000-row scale, after finding and fixing two real measurement biases along the way. Not yet wired into `Recommendation.tradeoffs` — the number isn't clean enough to show a user. See `SCALABILITY_BENCHMARK.md`.

### Next, ranked easy-to-hard
1. **Proper scalability experiment — more rows, not just 10,000.** Both the write-overhead benchmark and the HypoPG screening benchmark independently concluded the same thing: at 10,000 rows, real per-operation costs are small enough to sit near measurement noise. Testing across row counts (100K, 1M+) would show how costs actually scale instead of just confirming they're small at the one size ever tested — and is what would finally produce a wire-in-able write-overhead number.
2. **Real-world dataset validation.** Every result in this project comes from generated data — realistic in shape (verified Zipfian concentration numbers) but still synthetic. Running the same advisor against a real public dataset (e.g. an independent e-commerce dataset) would test whether recommendations and measured improvements hold up outside data this project's own generation code produced.
3. **Wire CI as an actual deploy gate.** Currently CI (above) and deployment (Render/Vercel auto-deploy on push) are two unconnected systems — a push that fails CI still deploys; a push that passes still deploys regardless of CI. Closing this means disabling each platform's git-auto-deploy and triggering a deploy hook only after CI passes. **Deliberately deferred, not forgotten** — explicit call made this round that CI's real value (a visible pass/fail signal on every push) is already captured, and this is a hardening step for later, not MVP-blocking for a two-person project where "check CI is green before you consider a push done" is a reasonable enough process for now.
4. **Multi-condition/non-equality JOIN support** — currently only single-condition equi-joins parse; a documented, deliberate scope boundary, not a silent gap.
5. Selectivity-based composite column ordering (replace alphabetical for filter-only composites).
6. `GROUP BY`/`HAVING` awareness in `SqlParser`/`CandidateGenerator` (`ORDER BY` now done).
7. Expose `IndexSetBudget` on `AnalyzeWorkloadRequest` — the engine capability exists, the HTTP request shape doesn't carry it yet.
8. Wall-clock timeouts / async cancellation and rate-limiting on the public API — `ApiLimits` bounds request size, not how long a compliant request is allowed to run.
9. Logging framework, unify `Main.java`'s config with `application.properties`.

---

## Immediate Next Task

**A proper scalability experiment.** The system is now genuinely live — [index-advisor.vercel.app](https://index-advisor.vercel.app) (frontend) talking to [index-advisor.onrender.com](https://index-advisor.onrender.com) (backend) — with a real CI pipeline running the full 94-test suite against a real Postgres + HypoPG on every push (visible pass/fail, not yet wired to gate the deploy itself — see "Next," above), and both single-query and workload analysis verified end to end. No functional gaps remain in either analysis mode, and engine maturity is no longer the limiting factor. What's left is a validation question, not a capability one: every benchmark in this project (HypoPG screening, write overhead) has been run at exactly one scale — 10,000 rows — and both independently found real costs too small to cleanly measure at that size. A row-count-scaling experiment would resolve that, and real-world dataset validation would test whether any of this holds up on data this project didn't generate itself.

Also outstanding, not urgent but real: **rotate the Supabase credentials** hardcoded in the teammate's `Server.cpp` (now public on GitHub) — a project-owner action, not something done from this session. And the two disclosed Docker Compose gaps (`backend/.env` missing, `VITE_API_URL` not passed as a build ARG) from Local Dev Sync, above — small, don't affect the live deployment, still open.
