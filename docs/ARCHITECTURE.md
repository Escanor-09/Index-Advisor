# Index Advisor — Architecture & Design Rationale

Why the project is built the way it is. For build status, see `CURRENT_PROGRESS.md`. For interview framing, see `INTERVIEW_NOTES.md`.

## System Architecture

```
React Frontend (planned, Milestone 15)
      ↓
HTTP API (Spring Boot, Milestone 14 — DONE)
      ↓
Java Advisor Engine (Milestones 2–13 — DONE)
      ↓
PostgreSQL
```

Note: an earlier planning document described a separately-tracked C++ advisor engine as "completed." That code does not exist anywhere in this repository — no `backend/` C++ sources were ever found. If it exists, it lives in a different codebase (e.g. a collaborator's) and is untracked here. Everything in these three docs reflects only what is verifiably present in this repository.

## Database Architecture

Two Postgres targets, deliberately separate:

```
                    SHARED
              Supabase PostgreSQL (ecom)
                      │
                      │ pg_dump / restore
                      ▼
                 LOCAL
             PostgreSQL (index_advisor)
                      │
             ┌────────┴────────┐
          TablePlus       Java Advisor
             └────────┬────────┘
                 Experiments
```

**Supabase (`ecom`):** shared canonical dataset, collaboration, source of truth for generated data.

**Local (`index_advisor`):** experimentation — `EXPLAIN ANALYZE`, real index creation/deletion, benchmarking. Kept separate specifically so real DDL experiments never touch the shared dataset. This separation is enforced, not just conventional — see Decision #6 below.

---

## Design Principle

Do not combine all responsibilities into one class:

```
PostgresManager       → talk to PostgreSQL (JDBC)
SqlParser             → understand SQL (JSqlParser)
QueryPlan             → understand a PostgreSQL EXPLAIN plan
WorkloadAdvisor        → understand workload-level access patterns
CandidateGenerator     → generate plausible candidate indexes
IndexEvaluator         → measure a candidate's actual before/after impact
RecommendationEngine   → choose which candidates are worth recommending
QueryAdvisor           → orchestrate single-query analysis
WorkloadQueryAdvisor    → orchestrate workload-level analysis
```

## Core Philosophy

Not "WHERE column exists → create index." Instead:

```
SQL Workload → Understand access patterns → Generate plausible candidates
   → Ask PostgreSQL how the query behaves (EXPLAIN ANALYZE)
   → Experiment with candidate indexes (create → measure → drop)
   → Consider workload frequency → Recommend only beneficial indexes
```

An index is useful only when its performance benefit outweighs its cost (storage, write overhead, maintenance). Current implementation measures benefit precisely and cost not at all (see Known Limitations in `CURRENT_PROGRESS.md`) — the architecture leaves room for it (Milestone 16).

**Original manual experiment that informed the evaluator's design** (done before `IndexEvaluator` existed): `EXPLAIN (ANALYZE, BUFFERS) SELECT * FROM products WHERE category_id = 25;` — without an index, `Seq Scan`, ~2.86ms; after `CREATE INDEX idx_products_category_id ON products(category_id);`, `Bitmap Index Scan → Bitmap Heap Scan`, ~0.74ms (~74% improvement, ~2 rows out of 10,000). `IndexEvaluator` was later built to reproduce this automatically, and did (Milestone 10).

---

## Architectural Decisions

Every non-obvious engineering choice, in roughly pipeline order.

### 1. Plain JDBC, not an ORM
`PostgresManager` uses raw `java.sql.Connection`/`Statement`/`ResultSet`, not Hibernate/JPA/Spring Data. The entire tool exists to construct and inspect *exact* SQL text (`EXPLAIN ANALYZE ...`, `CREATE INDEX ...`) and read Postgres's own JSON plan output. An ORM's job is to hide SQL from the developer — the opposite of what's needed. Boilerplate cost is small and fully contained in `PostgresManager`.

### 2. No connection pooling — a fresh `Connection` per call
Explicit choice to keep `PostgresManager` simple while call volume was small. Stopped being free once Milestone 13 introduced ~15+ DB round-trips per run, and matters more now the HTTP API (Milestone 14) can receive concurrent requests. Tracked as a Future Improvement (HikariCP), not silently ignored.

### 3. Jackson tree model (`JsonNode`), not POJO binding, for `QueryPlan`
EXPLAIN JSON shape varies by node type (`Seq Scan` vs `Index Scan` vs `Bitmap Heap Scan`, eventually join nodes) and by which EXPLAIN options are on. Modelling every node type as a class up front would need constant upkeep. `.path("Total Cost")`-style access degrades gracefully (missing field → default, not exception) and needed zero changes when Milestone 13 exercised new node types Milestone 6's single original test case never touched.

### 4. Records for every pure data type
`ParsedQuery`, `CandidateIndex`, `EvaluationResult`, `WorkloadQuery`, `WorkloadProfile`, `QueryEvaluation`, `WorkloadEvaluationResult`, `Recommendation`, plus the Milestone 14 web DTOs (`AnalyzeRequest`, `AnalyzeWorkloadRequest`, `ErrorResponse`) are all `record`s. Each is created once and never mutated. `CandidateIndex`'s record-generated `equals`/`hashCode` is load-bearing, not incidental — `CandidateGenerator.generateForWorkload()` deduplicates via `LinkedHashSet<CandidateIndex>`, which only works because two candidates with the same table and columns are automatically `.equals()`. Classes with actual behavior (`PostgresManager`, `SqlParser`, `QueryPlan`, `CandidateGenerator`, `IndexEvaluator`, `RecommendationEngine`, `WorkloadAdvisor`, `WorkloadIndexEvaluator`, `WorkloadRecommendationEngine`, `QueryAdvisor`, `WorkloadQueryAdvisor`, `WorkloadReader`, the Milestone 14 web classes) are ordinary classes.

### 5. Real `CREATE INDEX`/`DROP INDEX` cycles, not planner estimates alone
`IndexEvaluator` always creates the real index and runs `EXPLAIN ANALYZE` (which actually executes the query), rather than trusting a plain `EXPLAIN` cost estimate against a hypothetical index. Planner estimates depend on fresh statistics and correct cost constants (`random_page_cost`, etc.) — either can be wrong. Real measured execution time is the ground truth the whole project is built around (Core Philosophy). Trade-off: doesn't scale to hundreds of candidates — `HypoPG` (not yet built) is the known fix, deliberately deferred rather than built prematurely.

### 6. A hard runtime safety check, not developer discipline
`IndexEvaluator`'s constructor queries `SELECT current_database()` and throws unless it's exactly `index_advisor`. The two-database split (Supabase vs. local) only means anything if something *enforces* it — a comment or README warning isn't enforcement, a runtime check that throws is. Verified in practice, repeatedly: no experimental index was ever left in either database, across Milestones 10, 13, and 14's much larger HTTP-driven test volume.

### 7. Identifiers are quoted and schema-validated before reaching DDL — hardened in Milestone 14
`IndexEvaluator` builds `"CREATE INDEX " + name + " ON " + table + " (" + columns + ");"` via concatenation — not a stylistic shortcut, JDBC `PreparedStatement` binding (`?`) can only bind *values*, never *identifiers*; there is no parameterized way to write `CREATE INDEX ? ON ?(?)` in any JDBC driver. Before Milestone 14, this was safe only because every identifier originated from `SqlParser`'s parse of internally-generated queries.

That stopped being true once `/analyze` accepted HTTP input, so it was fixed as part of Milestone 14, not deferred. Investigated concretely: PostgreSQL's JDBC driver runs `Statement.execute()` via the simple query protocol, which permits multiple `;`-separated statements in a single call — a crafted column name like `category_id; DROP TABLE customers; --`, extracted by `SqlParser` from attacker-supplied SQL, could execute as a second statement. Closed with two layers, both inside `IndexEvaluator` (same principle as Decision #6 — enforce at the point of risk):
1. **Schema allowlist**, loaded once at construction from `information_schema.tables`/`.columns`, case-normalized to lowercase (matching Postgres's own unquoted-identifier folding). Only genuinely existing tables/columns may ever reach a DDL string.
2. **Quoted identifiers** in generated DDL, as defense-in-depth.

Verified with the actual payload via a real HTTP request: rejected with `400`, `customers` confirmed untouched via `psql` afterward.

### 8. `CandidateIndex.appliesTo()` is a subset check, not full leftmost-prefix modelling
A candidate applies to a query if same table and every candidate column is filtered on by that query — order-independent, no partial-prefix credit. Postgres's real leftmost-prefix rule is more permissive (an index on `(a,b,c)` can serve a query filtering on just `a`), but `CandidateGenerator` already generates a dedicated single-column candidate for every filter column separately, so "just one column of what would be a composite" is already covered independently. Deliberate scope limit, not oversight.

### 9. Candidates: one per column plus one full composite — not the power set
`CandidateGenerator.generate()` produces `filterColumns.size() + 1` candidates per query. Generating every subset/ordering grows combinatorially. Deliberate scope control ahead of JOIN support (which would add many more filterable columns per query), not an oversight.

### 10. Greedy algorithm for index-SET selection, not an exact optimizer
Choosing an optimal index set — maximize benefit under candidate overlap — is structurally Set Cover/Knapsack-shaped, NP-hard in general. `WorkloadRecommendationEngine.chooseIndexSet()` uses a greedy "best marginal contributor first" heuristic — a well-known, reasonable approximation, and not coincidentally close in spirit to real index-tuning research (Microsoft's AutoAdmin / Chaudhuri & Narasayya line of work rests on similar marginal-utility reasoning, with considerably more machinery). An exact ILP/DP formulation was considered and explicitly not attempted — disproportionate engineering at the current 9-candidate scale, noted as a Future Improvement, not an unconsidered gap. Known limitation: greedy isn't guaranteed globally optimal, though no visibly wrong answer has been observed on the current workload.

**Algorithm, concretely:**
1. For each not-yet-chosen candidate, compute a *marginal* score: sum of `improvementPercent` over its applicable queries that clear a 10% per-query minimum *and* aren't already covered by a previously chosen candidate.
2. Pick the candidate with the best marginal score; if the best remaining score is below 10, stop.
3. Mark every query that candidate helped (≥10%) as covered; remove it from the pool; repeat.
4. Each chosen candidate's *reported* impact uses its full true set of helped queries, not just the marginal ones credited at selection time — marginal accounting is purely an internal selection mechanism.

Real proof this matters: `products(category_id, status)` was excluded from the final workload-level set because `products(category_id)` — chosen first for covering 3 queries vs. the composite's 1 — already covered the composite's only relevant query. The per-query `QueryAdvisor` (Milestone 12), evaluating that query in isolation, recommended *both*. The workload-level view catches redundancy the per-query view structurally cannot see.

### 11. "Raw measurement" types are kept separate from "decision" types
`EvaluationResult`/`WorkloadEvaluationResult` (unfiltered, includes negative/below-threshold results) are distinct from `Recommendation` (filtered, ranked, decided). Mirrors the Core Philosophy's measure-before-deciding stance — filtering thresholds, ranking, or tiering can change without re-running any real (slow) database experiments, since raw measurements persist independently of what was decided from them.

### 12. `WorkloadAdvisor` (understand) and `WorkloadQueryAdvisor` (decide) are deliberately separate
`WorkloadAdvisor.analyzeWorkload()` only produces a `WorkloadProfile` — no candidates, no evaluation, no recommendation. `WorkloadQueryAdvisor` is the separate top-level class that calls `WorkloadAdvisor`, then `CandidateGenerator`, then `WorkloadIndexEvaluator`, then `WorkloadRecommendationEngine`. Matches the Design Principle stated from the project's start and keeps that boundary real in code, not just documented.

### 13. `Main.java` as the verification method — known, temporary
Every milestone through 14 was verified by adding a demo method to `Main.java` that runs the real pipeline against the real local database and prints output. Fast, genuinely real, zero-mocking verification at every step. Explicitly *not* the intended end state — no automated, assertion-based test suite exists (Future Improvement #4, `CURRENT_PROGRESS.md`).

### 14. `measureStable()` — warm-up + repeated-sample median, applied symmetrically
Added to fix a live-discovered bug (see `INTERVIEW_NOTES.md` for the full story): `CREATE INDEX` requires a full table scan to build the index, which warms the OS/Postgres page cache regardless of whether the resulting index is ever used — so a single-sample "after" measurement could look faster purely from that warm-up, not from the index doing anything. Fix: 1 discarded warm-up run + 3 measured runs on *both* sides of the before/after comparison, taking the median execution time. Node type and estimated cost are stable across repeated runs of the same query/schema state — only timing is noisy — so the median-time sample's other fields are exactly as representative as any other sample's.

### 15. Same-plan-shape improvement clamp
`measureStable()` alone reduced but didn't eliminate the cache-warming artifact above (~40% → ~16.7%), because `CREATE INDEX`'s table-scan happens *between* the before and after measurement blocks — by the time "after" runs its own warm-up, the table has accumulated more total I/O than "before" ever saw, even with a symmetric protocol on each side individually. Rather than chase a fully cache-neutral protocol (bigger, more invasive), `IndexEvaluator.computeImprovement()` clamps `improvement` to `Math.min(improvement, 0.0)` whenever the plan shape is unchanged: if Postgres didn't change strategy anywhere in the plan, there is no mechanism by which the query got structurally faster, so any remaining positive delta is definitionally noise. Closes the gap at its root rather than averaging around it.

Originally implemented as `before.getNodeType().equals(after.getNodeType())` — root node only. **This was itself a bug, found via JOIN support testing (Decision #20 below)**: for a join query the root node is the join *strategy* (e.g. `Nested Loop`), which can legitimately stay identical while a *nested* child scan changes beneficially. Comparing only the root wrongly clamped a genuine ~93% improvement to zero for exactly the scenario JOIN support exists to handle. Fixed by comparing `QueryPlan.getAllNodeTypes()` (a depth-first walk of the *entire* plan tree) instead of just the root — behavior-preserving for every single-table case, since a leaf plan's `getAllNodeTypes()` is just `[rootType]`, identical to the old comparison; confirmed by the full pre-existing test suite passing unchanged after the fix.

### 16. Connection pooling (HikariCP), not a fresh `Connection` per call
`PostgresManager` now owns a `HikariDataSource`. This was Decision #2's stated, deferred trade-off, revisited once the HTTP API (Milestone 14) made concurrent requests real rather than hypothetical. Public constructor signature deliberately unchanged (`PostgresManager(url, user, password)`), so every existing caller (`Main.java`, `AdvisorConfig`) needed zero changes — the pooling is entirely internal.

### 17. Index names include a per-call unique suffix, not just table+columns
Found via concurrent-load testing (5 identical concurrent `/analyze` requests, 4 failed with `500`): `IndexEvaluator.buildIndexName()` built a *deterministic* name from table+columns alone, so two concurrent requests evaluating the same candidate raced on `CREATE INDEX` — one succeeded, the rest hit "relation already exists." This bug existed structurally since Milestone 10; it simply wasn't observable until connection pooling (Decision #16) removed the per-call connection-establishment latency that had been accidentally serializing concurrent requests. Fixed with an `AtomicLong` counter appended to every generated index name. The pre-emptive `DROP INDEX IF EXISTS` at the start of `evaluate()` (Decision #6's era) stays — it now guards against stale names from a *previous JVM run* rather than a same-run collision, since the counter resets to 0 each time the process starts and could otherwise legitimately collide with leftover state from a prior crashed run using the same low counter values.

### 18. `PostgresManager.openSession()` / `ConnectionSession` — a deliberate exception to Decision #16's per-call pooling
Added specifically for `HypoIndexEvaluator`. HypoPG's hypothetical indexes are **session-local** (per physical connection) state — invisible from any other connection, including a different one borrowed from the same pool. Every other `PostgresManager` method independently borrows *a* connection per call, which is correct for ordinary queries/DDL (real, persisted database state, visible from any connection) but would silently break HypoPG usage: a hypothetical index created in one call might not exist from the next call's perspective. `openSession()` pins one physical connection for a whole sequence of statements that must see each other's session-local state. This was identified and fixed *before* writing `HypoIndexEvaluator`'s logic, not discovered as a bug afterward — the risk was reasoned through from HypoPG's documented session-local behavior once pooling was already in place.

### 19. HypoPG demonstrated and cross-validated as a standalone capability, not yet wired into the selection pipeline
`HypoIndexEvaluator` is verified correct — its estimated-cost screening agreed with `IndexEvaluator`'s real measurements on all 3 test candidates, including correctly predicting zero benefit where real measurement also found none (Decision #5 predicted this trade-off's fix would look like this; this is that fix, built and proven). Deliberately not (yet) wired into `WorkloadQueryAdvisor`'s actual candidate selection as a screen-then-validate two-phase process: at the current ~10-candidate scale, `IndexEvaluator`'s real DDL cycle is not the bottleneck, so forcing the pipeline rewire now would be solving a problem that doesn't yet exist, at real risk to an already-verified, working pipeline. The mechanism existing and being proven trustworthy is the actual prerequisite; wiring it in is scoped as a Future Improvement, triggered by candidate-count growth (e.g. once JOIN support or workload scaling pushes candidate counts into the hundreds), not done speculatively now.

### 20. JOIN support: extend `ParsedQuery`'s data, don't replace its contract
`ParsedQuery` gained `qualifiedFilterColumns: List<TableColumn>` and `joins: List<JoinClause>` as its *stored* components, but `tableName()` (still stored) and `filterColumns()` (now a *derived* method computing from `qualifiedFilterColumns`, not a stored field) were deliberately kept returning exactly what they returned before JOIN support existed. Every caller written before JOIN support — `CandidateGenerator`'s original filter-candidate loop, `WorkloadAdvisor`'s frequency counting, every pre-JOIN test — kept working with zero code changes. This was verified, not assumed: the full pre-existing test suite and CLI demo were re-run after the change and produced byte-for-byte identical results for every single-table case. `ParsedQuery.singleTable(table, columns)` was added as a factory for the common case, used throughout the test suite, so callers that only care about the single-table shape never need to know `TableColumn`/`JoinClause` exist.

Only simple, single-condition equi-joins are recognized (`ON left.col = right.col`, both sides plain `Column` references). Multi-condition `ON` clauses, non-equality joins, and joins against a non-`Table` `FromItem` (e.g. a subquery) are a stated scope boundary: `SqlParser` skips them silently rather than guessing at a shape it can't verify — a wrong guess here would propose a misleading candidate index, which is worse than proposing none.

### 21. Data realism over row count — and calibrated empirically, not mathematically
Decided to fix `order_items`' artificial 1:1-with-`orders` ratio and add skewed (non-uniform) customer/product activity *before* considering any increase in row count. Rationale: a Zipfian distribution shows its characteristic long tail at 10,000 rows exactly as clearly as at 1,000,000 — skew is a property of distribution *shape*, not row *count* — so this delivers the demo/realism value increasing size would have, without the added operational surface area (slower tests, a second database tier, more to keep in sync) that a size increase brings.

`createZipfianSampler()` (`src/populate/random-utils.js`) uses rank-based weighting (`weight(rank i) = 1/(i+1)^exponent`) with a precomputed cumulative-weight array and binary-search sampling — O(log n) per draw after an O(n) one-time setup, cheap enough to call tens of thousands of times per populate run. `weightedChoice()` handles the separate, simpler case of a small fixed set of discrete outcomes with explicit weights (items-per-order: 1–5).

**The exponent values were wrong on the first attempt, and this was caught by verification, not code review.** Initial choice (1.1 for customers, 1.2 for products) was picked as "a moderate-sounding number" without checking actual output. Real result, found by querying the generated data directly: one customer received 1,496 of 10,000 orders (15%), one product appeared in 4,113 of 23,719 line items (17%) — a single monopolizing outlier, not realistic skew. This is the same discipline applied everywhere else in this project (measure the real thing, don't trust the math in isolation) — the fix was to query top-10%-share and max-single-item concentration after every attempt and adjust the exponent based on that number, landing on 0.5/0.6, not to reason more carefully about the formula in the abstract.

**Local before shared:** the same regeneration was validated against the local `index_advisor` sandbox first — full integrity re-check, all 42 JUnit tests, full CLI demo — before being applied to the shared Supabase `ecom` database. This mirrors Decision #6's reasoning (the local/Supabase split exists specifically so risky operations have a safe place to run first) extended to the data-generation layer, not just to `IndexEvaluator`'s DDL. The truncate-and-regenerate step against Supabase was also treated as consequential enough to require explicit confirmation before executing, not assumed to be pre-authorized by "the user asked for this feature" — a destructive operation against shared infrastructure warrants a distinct confirmation from the feature decision that motivated it.

### 22. A teammate's parallel C++ backend — ported ideas natively, not called as a microservice
A teammate had independently built a second backend for the same problem (C++, `libpg_query`, HypoPG-only evaluation, `cpp-httplib`) on the repo's `main` branch, with a React frontend built against its exact HTTP contract. Decided by reading both codebases in full before judging: not a strict win for either side. His code had four genuine strengths this one lacked (ORDER BY-aware candidates, existing-index awareness, prefix-domination pruning, AI-generated explanations) and a more robust parser (`libpg_query` is the actual Postgres grammar, not a third-party reimplementation). This codebase had the more rigorous *engine* — real measured execution time instead of a planner cost estimate, a workload-level advisor his code doesn't have at all, and safety/concurrency/test hardening earned from real bugs found and fixed here.

Considered three shapes for combining them: (a) swap to his backend outright — inherits his real defects (thread-unsafe single shared connection under his multi-threaded server, no injection defense on dynamic DDL, no tests, hardcoded public credentials); (b) a true cross-language hybrid, where his C++ process is modified to expose a parse-only endpoint and this backend calls it over HTTP for candidate discovery before evaluating everything itself; (c) port his four genuine feature gaps natively into Java and keep this engine as the sole live system. Chose (c). Option (b) requires standing up and operating a second live service, in a second language, for something as small as candidate generation — a permanent two-process dependency to save what turned out to be a few hundred lines of well-understood, already-read logic. His code isn't discarded by this choice — it stays intact in the repo and git history; the value of what he built (the algorithms, not the binary) is what actually carried forward.

### 23. `ExistingIndexManager` — controlled string parsing of `indexdef`, not a general SQL parser
Discovering what indexes already exist reads `pg_indexes.indexdef` (e.g. `"CREATE INDEX idx ON public.orders USING btree (customer_id, created_at)"`) and extracts the table name and column list via bounded string search (`indexOf("public.")`, `indexOf(" USING")`, last matching parens) rather than a real SQL parser. Deliberately narrow, not naive: Postgres always schema-qualifies the table name in `indexdef` output regardless of `search_path`, and every index this project itself ever creates (`IndexEvaluator.evaluate()`) is a plain column list — never a functional index, partial index, or expression index — so the assumptions this parsing relies on are guaranteed by this codebase's own DDL-generation code, not just "probably true in practice." Coverage is a leading-prefix match (`isPrefix(candidate.columns, existing.columns)`), matching real b-tree semantics: an index on `(a, b)` can serve a query filtering on just `a`, but not one filtering on just `b` — a subset check would be wrong here in a way a prefix check isn't.

### 24. `RecommendationEngine.pruneDominated()` — domination is prefix-based, not just "fewer columns"
A candidate is dropped only if some *other* candidate on the same table has columns that are a leading prefix of its columns *and* an equal-or-better measured improvement. Prefix, not "any subset," for the same b-tree reason as Decision #23 — a `(status, category_id)` composite doesn't make a `(category_id)` single-column candidate redundant, since Postgres can't use the composite to serve a query that filters only on `category_id`. Runs before the existing `MIN_IMPROVEMENT_PERCENT` filter, not after — a dominated candidate that still clears the 10% bar (e.g. a 3-column composite at 92% when its 1-column prefix already measures 92%) would otherwise still surface as a spurious extra recommendation, adding real write/storage cost for measured zero incremental benefit.

### 25. `AiExplanationService` — best-effort by design, `java.net.http.HttpClient` over a new dependency
Calls Gemini to turn an already-measured recommendation into short natural-language prose. Deliberately outside the trust boundary of the actual recommendation: every failure mode (missing API key, network failure, timeout, malformed response, rate limit) is caught by one broad `catch (Exception e)` and turned into a clear fallback string, never an exception that could make `/generate-explanation` look like the core measurement failed. This is the one place in the codebase a broad catch is the right call — the whole point of the class is graceful degradation of a non-critical enrichment layer, not a boundary where swallowing a real bug would be dangerous. Used Java's built-in `java.net.http.HttpClient` (available since Java 11) plus Jackson (already present via `spring-boot-starter-web`) rather than adding a new HTTP client dependency — no new capability was actually needed.

### 26. `AnalyzeResponse` surfaces real measured milliseconds, not a cost estimate — and lives in `web`, not `recommendation`
The HTTP response DTO for `/analyze` is a new type in `advisor.web`, built by `AnalyzeResponse.from(QueryAdvisor.SingleQueryAnalysis)` from `QueryAdvisor.analyzeDetailed()`'s full result (parsed query + every evaluated candidate + the filtered recommendations) — not an extension of the `Recommendation` domain record, which stays focused on what it's used for elsewhere (`Main.java`'s CLI output, workload aggregation) and doesn't need raw before/after timings cluttering it. `originalCost`/`bestCost`/candidate `cost` fields are real measured execution time in milliseconds (`EvaluationResult.beforeExecutionTime`/`afterExecutionTime`), deliberately not the planner's cost estimate that field naming in the original reference frontend implied — surfacing an estimate under a label that reads as measured fact would undersell the one thing that actually distinguishes this engine from the alternative considered in Decision #22. Frontend labels (`ResultCard`, `CandidateTable`, `CostComparisonChart`) were updated to say "Time (ms)" for the same reason: the UI shouldn't claim to show something it isn't.

### 27. `WorkloadAnalyzeResponse` reuses `Recommendation.affectedQueries()` as the "helped" filter, rather than re-deriving the threshold
Same shape of problem as Decision #26 (the web layer needs real numbers `Recommendation` doesn't carry), same solution pattern (`WorkloadQueryAdvisor.analyzeDetailed()` exposes the raw `List<WorkloadEvaluationResult>`, a new `advisor.web` DTO builds the response) — but with one more wrinkle: a candidate's `WorkloadEvaluationResult.queryEvaluations()` includes *every* query it was evaluated against, including ones that didn't clear `WorkloadRecommendationEngine`'s `MIN_QUERY_IMPROVEMENT_PERCENT` bar. Averaging cost across all of them would let a barely-positive or negative result quietly drag down the reported number for an index that's actually a strong, clean win everywhere it counts. Rather than reimplementing that 10% threshold a second time in the web layer (two independent definitions of "helped" that could drift apart), `WorkloadAnalyzeResponse.from()` filters `queryEvaluations` down to exactly the SQL strings already present in `Recommendation.affectedQueries()` — the engine's own decision, reused as the source of truth, not re-derived.

`coverage` is deliberately `affectedQueries().size() / totalWorkloadQueries` (a fraction of the *whole* workload passed in), not a fraction of the candidate's own applicable-query subset — the latter would make every recommended index look close to 100% "coverage" almost by construction, since a candidate is only generated from columns some query already filters on.

### 28. Frontend query-source UI: three modes converge into one editable list, not three separate submit paths
`WorkloadOptimization.jsx`'s preset dropdown, free-text textarea, and quick-add chips all write into the same `queries` state array rather than each having its own "analyze" action — picking a preset doesn't preclude then typing a few more by hand, or removing ones you don't want. This was a deliberate simplification over building three parallel submission flows: one `handleAnalyze()`, one visible "cart" the user can edit before committing, regardless of how each query got there. The preset parser (`parseWorkloadText()`) is a direct JS port of `WorkloadReader.readQueries()` — same two rules, strip `--`-prefixed lines then split on `;` — so a preset file behaves identically whether it's read by the backend directly or fetched and parsed by the browser.

Adding to the list is deliberately append-only, not deduped — a repeated click of the same quick-add chip adds another copy, which is the intended fast path for building a large custom workload (e.g. toward 100 queries) from a small fixed set of chips. Safe with React's index-based list `key`/removal, so duplicate values in the array never cause a rendering or removal ambiguity.

### 29. `WorkloadHypoScreener` — a session-reuse overload added to `HypoIndexEvaluator`, not a rewrite
Wiring HypoPG into the live pipeline meant calling `HypoIndexEvaluator.evaluate()` potentially hundreds of times in one screening pass (candidates × applicable queries) — the existing single-call overload opens and closes its own pooled connection every time, which would itself become a real cost at that volume, undermining the point of screening being cheap. Rather than rewriting the class, added a second `evaluate(candidate, sql, ConnectionSession)` overload that reuses a caller-provided session, plus a new `openSession()` accessor — the original `evaluate(candidate, sql)` now just delegates to it. `Main.java`'s existing demo usage (one-off, single calls) needed zero changes; `WorkloadHypoScreener` opens one session and reuses it across its whole screening loop.

Screening promotes a candidate the moment HypoPG predicts it clears the same 10% threshold a real result needs to clear to ever be recommended (`WorkloadRecommendationEngine.MIN_QUERY_IMPROVEMENT_PERCENT`) — short-circuiting on the first qualifying query, since screening only needs a yes/no answer, not the full per-query picture `WorkloadIndexEvaluator` computes afterward for whatever gets promoted.

**The honest result, measured not assumed:** `SCALABILITY_BENCHMARK.md` shows a real but modest ~1.2x average speedup, ranging from a 10% *slowdown* to 46%, correlating with how much screening actually filters out — not with workload size. A workload of near-uniformly high-value candidates (point lookups on FK/PK-adjacent columns) pays the HypoPG round-trip cost for almost every candidate and then still pays the real-DDL cost for nearly all of them anyway. This was kept in as the honest finding, not tuned away or hidden behind a rounder-sounding number — the same measurement discipline the rest of this project already runs on.

### 30. `IndexSetBudget` — a second stopping condition, not a replacement for the marginal-score bar
`WorkloadRecommendationEngine.chooseIndexSet()` gained a `(results, budget)` overload rather than changing its existing signature's behavior — the no-arg overload now just calls it with `IndexSetBudget.UNLIMITED`, so every existing caller and test is byte-for-byte unaffected. The budget check (`chosen.size() >= maxIndexCount`, then cumulative storage vs. `maxStorageBytes`) runs immediately after the existing marginal-score check, inside the same greedy loop — deliberately not a separate post-processing pass over an already-chosen list, since a budget that's already been exceeded by then can't un-choose anything cleanly.

Doesn't hunt for a smaller candidate that might still fit once the greedy-best one doesn't — stops there, the same way the marginal-score check already stops rather than searching for a lesser candidate that still clears it. A disclosed simplification: the "always take the single best, stop the instant it's disqualified" invariant now holds for every stopping reason, not just some of them, at the cost of occasionally leaving budget unused that a smaller candidate later in the list could have fit into.

### 31. `QueryPlan.describeChange()` — computed once near the source data, not re-derived at each presentation layer
The nested-`observedEffect` bug existed because the information needed to fix it (the full before/after node-type sequence) was available in `IndexEvaluator.evaluate()`, where both `QueryPlan` objects are still in scope, but was discarded before reaching `RecommendationEngine`/`WorkloadRecommendationEngine`, which independently built a shallow `beforeNodeType -> afterNodeType` string each — the same shallow logic duplicated in two unrelated classes. The fix adds one new `EvaluationResult.observedEffect` field, computed once via `QueryPlan.describeChange(before.getAllNodeTypes(), after.getAllNodeTypes())` at the point of measurement, with both downstream classes now just reading it. This is the more correct fix than patching each call site's string-building independently — it removes the actual root cause (a single fact computed in two places) rather than aligning two copies of it.

`describeChange()` itself: identical sequences → "no plan change"; differing root → simple root-to-root (unchanged behavior for the common single-table case, where the root already *is* the whole plan); same root but a differing nested position → that position's change, labeled "(nested under `<root>`)"; same root, all shared positions equal, but differing tree sizes → a shape-changed message. Verified against the exact real query that originally exposed the bug, not just synthetic test fixtures.

### 32. `ApiLimits` — a shared constants class, checked before real work starts, not a request-shape change
Query-count/SQL-length validation (`WorkloadQueryAdvisor.validateWorkload()`, `QueryAdvisor.validateSql()`) and candidate-count validation (`WorkloadQueryAdvisor.validateCandidateCount()`) are separate checks at separate points in the pipeline, not one upfront gate — because they bound different, sequentially-incurred costs. Query count and SQL length are checkable the instant a request arrives, before `WorkloadAdvisor`'s profiling step runs a real `EXPLAIN ANALYZE` per query; candidate count isn't knowable until *after* candidates are generated from that profiling output, but still needs checking before the genuinely expensive phase (HypoPG screening, then real DDL evaluation) starts. Two gates, not one, because a single upfront check couldn't see the second cost yet.

All four validation methods are package-private, not private — the same pattern `IndexEvaluator.computeImprovement()` already established, specifically so they're directly unit-testable without constructing a `PostgresManager`. Existing `IllegalArgumentException` + `AdvisorController`'s already-wired `@ExceptionHandler` meant zero new controller code was needed — oversized requests already map to a clean 400, the same path `SqlParser`'s non-SELECT rejection and `IndexEvaluator`'s unknown-column rejection already use.

Deliberately doesn't cover wall-clock timeouts on a compliant-sized request, or rate-limiting — both real architectural additions (async MVC request handling, a rate-limit dependency) beyond what "reject oversized input fast" requires. Recorded as a disclosed next step (`CURRENT_PROGRESS.md`), not silently folded into this pass's scope.

---

## Data Structures Reference

Every record and its fields, with the reasoning behind its shape.

### `advisor.parsing.ParsedQuery`
```java
record ParsedQuery(
    String tableName,
    List<TableColumn> qualifiedFilterColumns,
    List<JoinClause> joins,
    List<TableColumn> qualifiedOrderByColumns
)
```
`filterColumns()` (a *derived* method, not a stored component) filters `qualifiedFilterColumns` down to just `tableName`'s columns, deduplicated and sorted alphabetically — preserving exactly what this type returned before JOIN support (Decision #20), so every pre-JOIN caller kept working unchanged. Sorting makes the composite candidate `CandidateGenerator` builds deterministic regardless of predicate order in the original SQL. `ParsedQuery.singleTable(table, columns)` is a factory for the common single-table case, used throughout the test suite and any caller that doesn't need to know `TableColumn`/`JoinClause` exist (it passes `List.of()` for both `joins` and `qualifiedOrderByColumns`). `qualifiedOrderByColumns` follows `qualifiedFilterColumns`'s exact shape — table-attributed, alias-resolved the same way — added so `CandidateGenerator` can propose sort-avoidance indexes (Decision #22's port from the C++ backend).

### `advisor.parsing.TableColumn`
```java
record TableColumn(String table, String column)
```
A column reference already resolved to its real table — aliases (`o.customer_id` → `orders.customer_id`) are resolved by `SqlParser` before a `TableColumn` is ever created, so nothing downstream needs to know about aliases at all.

### `advisor.parsing.JoinClause`
```java
record JoinClause(String leftTable, String leftColumn, String rightTable, String rightColumn)
```
One simple two-table equi-join condition, already alias-resolved. Deliberately narrow — see Decision #20 for why only this shape is recognized.

### `advisor.planning.QueryPlan` (class, not a record)
Wraps a parsed `JsonNode` tree (`root` = full EXPLAIN response, `planNode` = the `"Plan"` subtree) behind `getTotalCost()`, `getExecutionTime()`, `getPlanningTime()`, `getNodeType()`, `getRelationName()`, and `getAllNodeTypes()` (depth-first walk of the whole plan tree, added for Decision #15's fix — a single-table plan's `getAllNodeTypes()` is just `[getNodeType()]`, a join's includes every nested scan/join node too). A class because its job is behavior (safe extraction from variable-shaped external JSON with sensible defaults), not holding a fixed value set.

### `advisor.candidates.CandidateIndex`
```java
record CandidateIndex(String tableName, List<String> columns)
```
Plus `toDdlColumnList()`, `appliesTo(ParsedQuery)` (Decision #8, extended by Decision #20 to also match single-column candidates against either side of a `JoinClause`, and extended again this round to also match against `ParsedQuery.qualifiedOrderByColumns()` — without that, a pure sort-avoidance candidate would never be judged applicable to the very query that motivated it, silently breaking `WorkloadIndexEvaluator`), custom `toString()` (`table(col1, col2)`). Structural `equals`/`hashCode` are load-bearing for `CandidateGenerator.generateForWorkload()`'s deduplication.

### `advisor.candidates.ExistingIndex`
```java
record ExistingIndex(String tableName, List<String> columns)
```
One real index, as discovered from `pg_indexes` by `ExistingIndexManager` (Decision #23) — deliberately the same `(tableName, columns)` shape as `CandidateIndex`, since the whole point of this type is to be compared against a `CandidateIndex` via a prefix check, not to carry any information a real index has that a candidate doesn't (index name, access method, etc. are parsed out of `indexdef` but never kept).

### `advisor.evaluation.EvaluationResult` — single-query scope
```java
record EvaluationResult(
    CandidateIndex candidate,
    String beforeNodeType, String afterNodeType,
    String observedEffect,
    double beforeCost, double afterCost,
    double beforeExecutionTime, double afterExecutionTime,
    double improvementPercent,
    long indexSizeBytes
)
```
Both estimated cost *and* real execution time kept deliberately: cost is the planner's stable estimate; execution time is the real, noisier outcome. Keeping both lets a reader sanity-check whether the planner's expectation and reality agree — which is exactly how the cache-warming bug was first spotted (a positive `improvementPercent` next to an unchanged `nodeType`). `improvementPercent` computed once, in `IndexEvaluator.computeImprovement()`, including the Decision #15 clamp. `indexSizeBytes` is measured via `pg_relation_size()` while the experimental index still exists (between `CREATE` and `DROP`) — a real number, not an estimate. `observedEffect` (Decision #31) is the full-plan-shape diff (`QueryPlan.describeChange()`), computed once alongside `improvementPercent` rather than re-derived from `beforeNodeType`/`afterNodeType` downstream — those two fields alone can't distinguish "nothing changed" from "root just didn't move while a nested node did."

### `advisor.evaluation.HypoEvaluationResult`
```java
record HypoEvaluationResult(
    CandidateIndex candidate,
    String beforeNodeType, String afterNodeType,
    double beforeCost, double afterCost,
    double estimatedCostImprovementPercent
)
```
Deliberately has no execution-time fields, unlike `EvaluationResult` — a hypothetical index is invisible to real query execution (it doesn't physically exist), so there is no real time to measure, only the planner's cost estimate before/after. Kept as a separate type rather than reusing `EvaluationResult` with dummy time values, so the type system itself makes "this is an estimate, not a measurement" impossible to accidentally forget.

### `advisor.workload.WorkloadQuery`
```java
record WorkloadQuery(String sql, ParsedQuery parsedQuery, QueryPlan queryPlan)
```
One workload query, fully analyzed — bundling prevents re-parsing/re-running EXPLAIN when the same query needs examining from multiple angles later (profiling, then candidate generation).

### `advisor.workload.WorkloadProfile`
```java
record WorkloadProfile(List<WorkloadQuery> queries, Map<String, Integer> columnFrequency)
```
`columnFrequency` keys are flat `"table.column"` strings — deliberate simplicity, unchanged by JOIN support: `WorkloadAdvisor` still only counts frequency from `ParsedQuery.filterColumns()` (primary-table WHERE columns), not `qualifiedFilterColumns` or `joins`. A stated, deliberate scope boundary, not an oversight — profiling is presentational (Design Principle: "understand," not "decide"), while the actual candidate generation and evaluation pipeline (`CandidateGenerator`, `WorkloadIndexEvaluator`) is fully JOIN-aware regardless.

### `advisor.workload.QueryEvaluation`
```java
record QueryEvaluation(String sql, EvaluationResult result)
```
Pairs a raw result with which query produced it. `EvaluationResult` deliberately doesn't carry SQL text itself (kept minimal/reusable at single-query scope) — this is the pairing wrapper introduced specifically for Milestone 13's workload-level bookkeeping.

### `advisor.workload.WorkloadEvaluationResult`
```java
record WorkloadEvaluationResult(CandidateIndex candidate, List<QueryEvaluation> queryEvaluations, double workloadScore)
```
Raw measurement — `queryEvaluations` includes every applicable query regardless of outcome. `workloadScore` (sum of `improvementPercent`, negatives included) is a quick-glance signal only; the actual greedy algorithm recomputes its own marginal score per iteration, since "already covered" state changes across iterations.

### `advisor.workload.IndexSetBudget` (Decision #30)
```java
record IndexSetBudget(long maxStorageBytes, int maxIndexCount)
```
`UNLIMITED` (`Long.MAX_VALUE`/`Integer.MAX_VALUE`) plus `storageBytes(n)`/`indexCount(n)` factories for the common single-dimension cases. Sentinel max values chosen over `Optional` fields specifically so `WorkloadRecommendationEngine`'s budget checks are unconditional numeric comparisons at every call site — no null/empty-check branching needed just because a caller didn't set one dimension.

### `advisor.recommendation.Recommendation`
```java
record Recommendation(
    CandidateIndex candidate,
    String impact,                 // "HIGH" | "MEDIUM" | "LOW"
    List<String> affectedQueries,
    String reason,
    String observedEffect,
    double estimatedImprovementPercent,
    long indexSizeBytes,
    List<String> tradeoffs
)
```
The explainability-focused output — deliberately not `{candidate, bestResult}`. `impact` is a coarse tier so the headline signal is skimmable. `indexSizeBytes` carries the real measured storage cost as a number (for future programmatic use — sorting, budget constraints) separately from `tradeoffs`, which is the human-readable presentation of it (`"Additional index storage: 208.0 KB"`, via the static `Recommendation.formatBytes()` helper); the second `tradeoffs` line still says `"not measured"` for write/maintenance cost — honest about what is and isn't quantified. `report(int index)` formats all fields into a structured block, including an `Index size:` line.

### `advisor.database.PostgresManager.ConnectionSession` (nested class, not a record)
Not a data holder — a narrow-purpose class wrapping one pinned physical `Connection` with `executeScalar`/`executeUpdate` methods scoped to it, plus `close()` to release it back to the pool. Exists solely so `HypoIndexEvaluator` can run a sequence of statements that must all see each other's session-local HypoPG state (Decision #18) — every other database interaction in this codebase uses `PostgresManager`'s ordinary per-call-pooled methods instead, since ordinary queries/DDL are real, persisted state visible from any connection.

### `advisor.QueryAdvisor.SingleQueryAnalysis` (nested record)
```java
record SingleQueryAnalysis(
    ParsedQuery parsedQuery,
    List<EvaluationResult> allResults,
    List<Recommendation> recommendations
)
```
Returned by the new `QueryAdvisor.analyzeDetailed(sql)`; the pre-existing `analyze(sql) -> List<Recommendation>` now just calls it and returns `.recommendations()`, so every caller that only ever needed the filtered list (`Main.java`, the existing test suite) needed zero changes. Added because `AnalyzeResponse` (below) needs the *full* evaluated-candidate list and the parsed query to render a candidate table and a query-analysis breakdown — information `analyze()`'s return type never carried, by design, since `Recommendation` is deliberately a "decision," not a "measurement" (see the `EvaluationResult` vs. `Recommendation` split, `INTERVIEW_NOTES.md`).

### `advisor.web.AnalyzeResponse` (Decision #26)
```java
record AnalyzeResponse(
    String bestIndex, double originalCost, double bestCost, double improvement,
    List<CandidateView> candidates, QueryAnalysisView analysis
)
// + nested: CandidateView(index, cost, improvement), ColumnView(table, column),
//           QueryAnalysisView(tables, filterColumns, joinColumns, orderByColumns)
```
The HTTP-facing shape for `/analyze`, built by the static factory `AnalyzeResponse.from(SingleQueryAnalysis)` rather than being a `@RequestBody`/`@ResponseBody`-annotated version of an existing domain type — keeps the web layer's presentation concerns (a single "best" pick, a full ranked candidate table, milliseconds instead of the domain's mixed cost/time fields) out of `advisor.recommendation`, which has its own, different callers (`Main.java`'s CLI report, workload aggregation) that don't need any of this reshaping.

### `advisor.web.GenerateExplanationRequest` / `GenerateExplanationResponse`
```java
record GenerateExplanationRequest(String query, String bestIndex, double improvementPercent)
record GenerateExplanationResponse(List<String> reasoning)
```
Deliberately minimal — just enough context for `AiExplanationService`'s prompt. The original C++ contract's request also sent table/columns split out separately (and, in one code path, a field name — `table`, singular — that didn't match what its own frontend actually read from the analyze response); collapsing to a single `bestIndex` string removed that inconsistency rather than porting it forward.
