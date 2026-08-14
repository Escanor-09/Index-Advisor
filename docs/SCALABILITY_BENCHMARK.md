# Workload Scalability Benchmark

How workload analysis time scales with workload size, and what real speedup the HypoPG screen-then-validate pipeline (see `CURRENT_PROGRESS.md`, Priority Improvement — HypoPG wiring) actually delivers versus evaluating every candidate with real DDL. Measured, not asserted — this file exists specifically so the numbers below don't get overclaimed from memory later.

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
