# Index Advisor — Current Progress

What has been built, verified, and what's left. For *why* things are designed the way they are, see `ARCHITECTURE.md`. For interview-ready Q&A, see `INTERVIEW_NOTES.md`.

## Snapshot

- **Goal:** analyze SQL queries/workloads and recommend PostgreSQL indexes, using Postgres's own `EXPLAIN ANALYZE` as ground truth instead of a custom cost model.
- **Stack:** Java 21, Spring Boot 3.5.3, HikariCP-pooled JDBC, JSqlParser 5.3, Jackson, PostgreSQL, HypoPG extension. React frontend planned, not built.
- **Completion: 89%** on the original 16-milestone roadmap (milestone-weighted, see Roadmap below) — plus 5 additional priority improvements (below) not on that roadmap at all: quantitative storage cost, connection pooling, an automated test suite (42 tests), HypoPG-based screening, and JOIN support.
- **Core advisor engine — single-query and workload-level, with JOIN support — is fully built, verified against a real database and a real test suite, and exposed over HTTP.** What remains on the original roadmap is a frontend and deeper cost modeling.

---

## What's Built

### Milestone 0 — Database Foundation — DONE
7 tables (`categories`, `customers`, `products`, `orders`, `order_items`, `payments`, `shipping_addresses`), 10,000 rows in every table except `order_items` (see Data Quality Improvements below — no longer 1:1 with `orders`), 16 constraints (7 PK, 9 FK). Node.js populate scripts in `src/populate/`. All 18 integrity checks pass (`npm run verify`) — row counts, no orphaned FKs, cross-field consistency (`payments.amount` = `orders.total_amount`, etc.), no duplicate emails/IDs, no NULLs in mandatory columns. Two Postgres targets: Supabase `ecom` (shared canonical) and local `index_advisor` (experimentation sandbox, restored from `database/ecom_dump_*.sql`). Known schema gap: no `UNIQUE` constraint on `customers.email` at the DB level (uniqueness only holds because the generator constructs it that way).

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
`EvaluationResult`, `IndexEvaluator`. Real `EXPLAIN ANALYZE` → `CREATE INDEX` → `EXPLAIN ANALYZE` → `DROP INDEX` cycle. Hard safety check (`current_database() = 'index_advisor'` or throw). Guaranteed cleanup (pre-emptive `DROP INDEX IF EXISTS` + `finally`-block drop). Verified zero leftover indexes after every run, every milestone, without exception.

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

### Not Yet Started
Milestone 15 (React frontend) and Milestone 16 (index cost/storage/write-overhead modeling — partially informed by the "quantify tradeoffs" future improvement below).

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
    │       ├── QueryAdvisor.java                       single-query orchestrator (Milestone 12)
    │       ├── WorkloadQueryAdvisor.java                workload orchestrator (Milestone 13)
    │       ├── web/
    │       │   ├── IndexAdvisorApplication.java         @SpringBootApplication entry point (Milestone 14)
    │       │   ├── AdvisorConfig.java                    PostgresManager bean, reads application.properties
    │       │   ├── AdvisorController.java                POST /analyze, POST /analyze-workload, error handling
    │       │   ├── AnalyzeRequest.java                   record: query
    │       │   ├── AnalyzeWorkloadRequest.java            record: queries
    │       │   └── ErrorResponse.java                     record: error
    │       ├── database/
    │       │   └── PostgresManager.java                  HikariCP-pooled; executeScalar/executeColumn/explainAnalyze/
    │       │                                              executeUpdate; openSession()/ConnectionSession for
    │       │                                              same-connection sequences (HypoPG)
    │       ├── parsing/
    │       │   ├── ParsedQuery.java                       record: tableName, qualifiedFilterColumns, joins
    │       │   │                                          (+ filterColumns() derived, singleTable() factory)
    │       │   ├── TableColumn.java                       record: table, column
    │       │   ├── JoinClause.java                        record: leftTable, leftColumn, rightTable, rightColumn
    │       │   └── SqlParser.java                         SQL text -> ParsedQuery (JSqlParser, alias-aware, JOIN-aware)
    │       ├── planning/
    │       │   └── QueryPlan.java                         EXPLAIN JSON -> typed accessors + getAllNodeTypes()
    │       │                                              (depth-first full plan shape, not just root)
    │       ├── candidates/
    │       │   ├── CandidateIndex.java                    record: tableName, columns (+ appliesTo — filter- and
    │       │   │                                          join-column aware — toDdlColumnList)
    │       │   └── CandidateGenerator.java                 ParsedQuery -> List<CandidateIndex>, per-table filter
    │       │                                               candidates + per-side join candidates
    │       ├── evaluation/
    │       │   ├── EvaluationResult.java                   record: + indexSizeBytes
    │       │   ├── HypoEvaluationResult.java                record: HypoPG screening result (estimated cost only)
    │       │   ├── IndexEvaluator.java                      CREATE/measure/DROP, DB safety guard, schema allowlist,
    │       │   │                                            measureStable, unique index names, computeImprovement
    │       │   │                                            (full plan-shape clamp), pg_relation_size
    │       │   └── HypoIndexEvaluator.java                  hypopg_create_index/EXPLAIN/hypopg_reset via
    │       │                                                one pinned connection
    │       ├── workload/
    │       │   ├── WorkloadReader.java                      queries.sql -> List<String>
    │       │   ├── WorkloadQuery.java                       record: sql + ParsedQuery + QueryPlan
    │       │   ├── WorkloadProfile.java                     record: List<WorkloadQuery> + column frequency map
    │       │   ├── WorkloadAdvisor.java                      List<String> -> WorkloadProfile (profiling only)
    │       │   ├── QueryEvaluation.java                      record: sql + EvaluationResult pairing
    │       │   ├── WorkloadEvaluationResult.java             record: candidate + all its QueryEvaluations
    │       │   ├── WorkloadIndexEvaluator.java               candidate x workload -> WorkloadEvaluationResult
    │       │   └── WorkloadRecommendationEngine.java          greedy index SET selection
    │       └── recommendation/
    │           ├── Recommendation.java                       record: + indexSizeBytes, formatBytes()
    │           └── RecommendationEngine.java                  single-query filter + rank + tier
    └── test/java/advisor/
        ├── parsing/SqlParserTest.java                        10 tests
        ├── candidates/CandidateGeneratorTest.java              7 tests
        ├── candidates/CandidateIndexTest.java                  8 tests
        ├── recommendation/RecommendationEngineTest.java        4 tests
        ├── workload/WorkloadRecommendationEngineTest.java      4 tests
        ├── evaluation/IndexEvaluatorLogicTest.java             6 tests
        └── evaluation/IndexEvaluatorIntegrationTest.java       3 tests (real database)
```

30 main Java files, 7 test files (42 tests, 0 failures), 1 `pom.xml`, 1 `application.properties`, 1 workload file. Verified via `mvn test` (fast, repeatable), `mvn exec:java` (full CLI demo against the real database), and live `curl` against a real running server.

---

## Known Limitations

Organized by category. Each is a deliberate scope boundary or accepted trade-off — nothing here is a surprise, all flagged proactively.

### SQL parsing and understanding
- **~~Single-table queries only~~ — JOIN support added.** Simple two-table equi-joins (`ON left.col = right.col`) parse correctly, alias-resolved, with WHERE columns attributed to their real table. **Still limited:** only single-condition equi-joins recognized — multi-condition `ON` clauses, non-equality joins, 3+ table chains, and joins against non-table `FromItem`s (subqueries) are a stated scope boundary, skipped rather than guessed at.
- **`JSqlParser`, not Postgres's real grammar** (ANTLR-based). Bounded/mitigated, not eliminated.
- **No subquery/CTE/`GROUP BY`/`HAVING`/`ORDER BY` awareness.** Only WHERE-clause predicates (and now join columns) contribute to candidates.

### Candidate generation
- **Not the full power set** — one candidate per filter column + one full composite, per table. Partial composites and alternate orderings never generated.
- **Composite column order is alphabetical**, not selectivity-based.
- **No expression indexes, `INCLUDE` (covering) indexes, or partial indexes.**
- **`appliesTo()` subset check for filters, exact-match for join columns** — not full leftmost-prefix modelling.

### Evaluation
- **~~Real DDL doesn't scale~~ — HypoPG built and cross-validated, not yet wired into the selection pipeline.** `HypoIndexEvaluator` works and agrees with real measurements on all 3 test candidates, including correctly predicting zero benefit where real measurement also found none. Not yet used for actual screen-then-validate set selection in `WorkloadQueryAdvisor` — a real capability sitting ready, not yet load-bearing, since current candidate counts (~10) don't yet make real-DDL evaluation the bottleneck.
- **~~Single-sample timing~~ — fixed, twice.** `measureStable()` (median of 3 + warm-up) plus a same-*full-plan-shape* clamp (not just root node type — that was itself a bug, found via JOIN testing, see Priority Improvements). Verified: the live-observed ~40% cache-warming artifact now measures ~-5.6%, matching the original pre-bug finding; a genuine ~93% join-column improvement that the root-only clamp had been silently discarding is now correctly credited. Residual: still single-machine, single-point-in-time; still no concurrent-load simulation.
- **No write-path cost measurement.** Benefit side is measured precisely (including real index storage size, added this round); cost side (INSERT/UPDATE/DELETE overhead) isn't measured at all — `Recommendation.tradeoffs`' second line says so explicitly (`"not measured"`) rather than implying otherwise.
- **`Recommendation.observedEffect` can be misleadingly uninformative for join queries** — e.g. `"Nested Loop -> Nested Loop"` when the real change is in a nested child node. The underlying number and recommend/don't-recommend decision are both correct (fixed); the *displayed description* just doesn't yet reflect nested-node changes. Logged as a small, separate polish item, not folded into the correctness fix.
- **Single machine, single point in time** — numbers are reproducible in shape, not exact figures.

### Index-set selection
- **Greedy, not globally optimal.** Index selection is structurally Set Cover/Knapsack-shaped (NP-hard); greedy marginal-utility is a standard, defensible approximation, not a guarantee.
- **No storage/count budget constraint** — stops only on marginal-improvement threshold (real index size is now measured and available, just not yet used as a stopping condition).
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
- **Not containerized, no CI.**
- **Only tested locally** — no deployment.

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
Milestone 15  React Frontend Integration                          ← CURRENT
Milestone 16  Index Cost / Storage / Write-Overhead Modeling
```

**Completion: 89%** (8+2+4+3+6+7+5+3+6+7+12+9+6+10+5, weighted estimate table). Only frontend and cost/storage modeling remain on the original roadmap.

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

### Next, ranked easy-to-hard
1. **Wire `HypoPG` into `WorkloadQueryAdvisor`'s actual selection pipeline** (screen with `HypoIndexEvaluator`, validate only the greedy-chosen finalists with real `IndexEvaluator`) — the mechanism exists and is proven; this is the remaining integration work to make it load-bearing rather than a standalone demonstrated capability.
2. **Scale up the workload.** Extending `queries.sql` (including the 2 join queries currently only tested ad hoc in `Main.java`) is near-zero code effort; bumping dataset size just re-runs existing populate scripts with a higher row count.
3. **Fix `Recommendation.observedEffect` for nested plans** — use `QueryPlan.getAllNodeTypes()` (already built for the clamp fix) to describe what actually changed, not just the root node, e.g. surfacing which nested node changed rather than `"Nested Loop -> Nested Loop"`.
4. **Multi-condition/non-equality JOIN support** — currently only single-condition equi-joins parse; a documented, deliberate scope boundary, not a silent gap.
5. Selectivity-based composite column ordering (replace alphabetical).
6. `ORDER BY`/`GROUP BY` awareness in `SqlParser`/`CandidateGenerator`.
7. Write-overhead measurement (a real benchmark, not just storage size) to make `Recommendation.tradeoffs` fully quantitative.
8. Storage/count budget as an explicit stopping condition in `WorkloadRecommendationEngine` (the data — real index size — already exists).
9. Logging framework, unify `Main.java`'s config with `application.properties`, containerize, add CI.

---

## Immediate Next Task

**Milestone 15: React frontend.**

1. Scaffold a React app (Vite) as a new top-level `frontend/` directory, sibling to `backend/`.
2. Minimal first screen: textarea for a single query, workload-mode toggle/textarea (one query per line, matching `queries.sql`'s format), "Analyze" button hitting `/analyze` or `/analyze-workload` on `localhost:8080` (CORS needs enabling — `@CrossOrigin` on `AdvisorController` or a `WebMvcConfigurer`, since the dev server runs on a different port).
3. Render each `Recommendation` as a card using the exact fields already designed: index, impact badge, affected queries, reason, observed effect, estimated improvement, trade-offs.
4. Plan drill-down (`Query → Before Plan → After Plan`) needs the API to actually return plan JSON — currently `Recommendation` only carries `observedEffect` as a short string. Decide whether to extend `EvaluationResult`/`Recommendation` to carry raw plan JSON, or add it as a separate response field.
5. Deploy somewhere real once functional — a live URL is worth more than a localhost screenshot for placement purposes.

Connection pooling (HikariCP) is now in place, so a frontend making rapid successive requests is no longer the open risk it was — that gap was closed proactively before this milestone, not discovered by it.
