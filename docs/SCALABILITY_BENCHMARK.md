# Workload Scalability Benchmark

How workload analysis time scales with workload size, and what real speedup the HypoPG screen-then-validate pipeline (see `CURRENT_PROGRESS.md`, Priority Improvement — HypoPG wiring) actually delivers versus evaluating every candidate with real DDL. Measured, not asserted — this file exists specifically so the numbers below don't get overclaimed from memory later.

Also covers, as of 2026-08-16, the write-overhead benchmark (INSERT/UPDATE/DELETE cost of a recommended index) — see that section below.

## Tool

`backend/src/main/java/advisor/WorkloadBenchmark.java` — a standalone entry point, not a JUnit test (it takes real minutes by design; running it on every `mvn test` would make the suite unusable). Run it with:

```bash
cd backend
mvn exec:java -Dexec.mainClass=advisor.WorkloadBenchmark
```

For each case it: loads a sample workload file (`backend/workload/samples/`), profiles it, generates the full candidate set, then runs **both** pipelines back-to-back against the same candidates —

- **Screened** — the live pipeline (`WorkloadHypoScreener` first, then `WorkloadIndexEvaluator` only on promoted candidates)
- **Real-only** — every candidate evaluated with real DDL directly, reconstructing the pipeline's shape *before* HypoPG was wired in

— and reports wall-clock time for each, plus how many candidates were promoted.

**Disclosed caveat:** both passes run sequentially against the same warm database within one process, not in isolated cold-cache environments. Screened runs first deliberately (the more conservative ordering — it does the least real DDL, so leaves the least residual cache warmth for real-only to unfairly benefit from). The dominant cost difference here is real `CREATE`/`DROP INDEX` cycle count, not page-cache state, so this ordering choice matters at the margin, not to the headline result — but it's a single-machine, single-point-in-time measurement like everything else in this project, not a controlled lab benchmark.

## Results (2026-08-14, local `index_advisor`, 10,000 rows/table)

| Workload | Queries | Candidates | Promoted | Screened (ms) | Real-only (ms) | Speedup |
|---|---:|---:|---:|---:|---:|---:|
| `customer-support-lookups.txt` | 30 | 25 | 19 (76%) | 567 | 508 | **0.90x** |
| `product-catalog-browsing.txt` | 34 | 16 | 11 (69%) | 699 | 807 | 1.15x |
| `platform-wide-peak-traffic.txt` (first 25) | 25 | 15 | 9 (60%) | 351 | 511 | 1.46x |
| `platform-wide-peak-traffic.txt` (first 50) | 50 | 29 | 21 (72%) | 826 | 1143 | 1.38x |
| `platform-wide-peak-traffic.txt` (first 100) | 100 | 55 | 39 (71%) | 2524 | 2810 | 1.11x |

**Average speedup across these 5 cases: ~1.2x.** Range: a 10% *slowdown* to a 46% speedup.

## What this actually shows

**The speedup is real but modest, and it's not free — it correlates directly with how much screening actually filters out, not with workload size.** The clearest signal in the table isn't the query count column, it's the promotion percentage: `customer-support-lookups.txt` promotes 76% of its candidates (screening barely filters anything) and comes out *slower* with screening than without it — you pay the full HypoPG round-trip cost for every candidate and then still pay full real-DDL cost for nearly all of them anyway. The other four cases promote 60–72%, filtering out more genuine dead weight, and show real (if modest) wins.

**Why `customer-support-lookups.txt` is the worst case, specifically:** that workload (see `backend/workload/samples/`) is deliberately ID-driven point lookups — `WHERE id = 500`, FK joins on `order_id`/`customer_id`. Those are close to the textbook case *for* indexing: high selectivity, genuinely useful almost by construction. There's little "chaff" for HypoPG to correctly screen out, so the screening pass is close to pure overhead for this specific query shape.

**Why the win is modest even in the best case, and what would change that:** this project's tables are deliberately 10,000 rows (see `ARCHITECTURE.md` Decision #21 — data realism was prioritized over raw scale). At that size, a real `CREATE INDEX` is already cheap — there isn't that much real-DDL cost sitting on the table for screening to avoid. HypoPG's actual value proposition in production systems is at table sizes where a real index build takes real seconds-to-minutes, not milliseconds; this benchmark is honestly measuring a regime where that cost is already small, not the regime the architecture is really built for. That's a direct, disclosed consequence of a decision made earlier in this project, not a new problem.

**Correctness held throughout, independent of the speed question** — `WorkloadHypoScreenerIntegrationTest` (see `CURRENT_PROGRESS.md`) separately confirms the screened pipeline reaches the identical final recommendation a real-only pipeline would, on a known case. This benchmark is purely about time, not about whether screening changes the answer — it doesn't.

## Natural next step this benchmark surfaced, not yet built

Since the promotion ratio is what actually predicts whether screening helps, a reasonable refinement — **not implemented in this pass, flagged here rather than silently deferred** — would be to skip screening outright below some candidate-count threshold, or dynamically fall back to real-only evaluation when a quick sample of the workload suggests most candidates are already point-lookup-shaped. That's real additional engineering (needs its own validation before being trusted), so it's recorded as a finding, not built speculatively on top of an already-large round of changes.

---

# Write Overhead Benchmark

The benefit side of a recommendation has always been measured precisely (real `EXPLAIN ANALYZE` timing, real `pg_relation_size()`); the cost side never was — `Recommendation.tradeoffs`' write/maintenance line has literally said `"not measured"`. This measures the real INSERT/UPDATE/DELETE cost of adding an index, targeting `products(category_id)` — the project's single most-referenced candidate throughout (92–99% read improvement in every prior measurement).

## Tool

`backend/src/main/java/advisor/WriteOverheadBenchmark.java` — a standalone entry point, not a JUnit test, same reasoning as `WorkloadBenchmark`: it creates a real index and runs real DML for the duration of the run, which `mvn test` shouldn't pay for on every build. Run it with:

```bash
cd backend
mvn exec:java -Dexec.mainClass=advisor.WriteOverheadBenchmark
```

Every timed statement runs inside a transaction that is rolled back immediately afterward — real Postgres write-path cost (WAL, B-tree page maintenance) is genuinely paid and measured, but the dataset is left byte-for-byte unchanged afterward (verified: `SELECT count(*) FROM products` is 10,000 before and after every run, zero leftover `bench-temp` rows, zero leftover index). DELETE specifically measures a row inserted moments earlier in the same open transaction, not an existing real product — a real product can be referenced by `order_items`, which would make a real `DELETE` fail on a foreign-key violation rather than measure anything.

## Two real measurement biases found and fixed while building this

Both are the same class of bug already found once before on the read side (`IndexEvaluator.measureStable()`'s cache-warming fix) — worth stating plainly rather than presenting the final numbers as if the first attempt were clean:

1. **Page-cache asymmetry.** `CREATE INDEX` does a full sequential scan of `products`, warming the OS/`shared_buffers` cache. Left uncorrected, the "with index" phase — which always runs *after* `CREATE INDEX` — looked faster purely from that incidental warm-up, not from anything about the index itself. **Fixed** by running an equivalent full-table warm-up scan (`SELECT count(*) FROM products`) before the baseline phase too.
2. **JIT warm-up asymmetry.** The JVM's JIT compiler only reaches steady state after enough invocations. Since the baseline phase always runs first in wall-clock time, whichever phase ran *second* always looked faster purely from more total JIT-compiled invocations by that point — independent of the index entirely. This bias persisted even after fixing #1 (first attempt showed a suspicious, consistently negative "overhead" of -14% to -45% across 3 runs). **Fixed** with a large (300-iteration) JIT warm-up run once before either phase begins.

## Result: at this scale, real per-statement overhead is inside the noise floor

**2026-08-16, local `ecom_test`, 10,000 rows, 5 trials, median-of-41-measured-runs per trial:**

| Operation | Baseline (µs) | With index (µs) | Overhead |
|---|---:|---:|---:|
| INSERT | 97.4 | 87.4 | -10.3% |
| UPDATE (indexed column) | 76.8 | 80.2 | +4.4% |
| DELETE | 893.6 | 910.6 | +1.9% |

Per-trial breakdown (why an aggregate alone would be misleading): individual trials ranged from **-40.3% to +43.9%** for INSERT alone, flipping sign trial to trial with no consistent direction. That's not measurement failure — it's an honest result. **A single narrow B-tree index's real per-statement maintenance cost, on a 10,000-row table, is on the order of tens of microseconds — small enough to sit inside the noise floor of client-side JDBC round-trip timing on one local machine.** The qualitative conclusion this supports: for a table at this project's current scale, write overhead from one recommended index is genuinely small, not a meaningful trade-off against the read-side gains (which run 90%+ in the same measurements) — but the *exact* percentage above shouldn't be quoted as a precise fact, since the trial-to-trial variance is larger than the aggregate effect itself.

**What would resolve this cleanly, not yet done:** bulk-throughput measurement (many rows/sec sustained, not one row at a time) and/or a larger table, where real per-row overhead would need to add up to a measurable aggregate rather than getting lost in per-statement noise. This is exactly what the "proper scalability experiment with more rows" item (`CURRENT_PROGRESS.md`'s Future Improvements) would also need to build — the two aren't separate work.
