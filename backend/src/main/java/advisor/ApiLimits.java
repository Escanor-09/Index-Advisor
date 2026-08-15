package advisor;

/**
 * Upper bounds on what the public HTTP API will process in one request,
 * checked before any real database work happens — a workload or query
 * exceeding these is rejected with a fast 400, not accepted and left to
 * run for an unbounded amount of time doing real CREATE/DROP INDEX cycles.
 * Real cost is combinatorial, not linear: WorkloadHypoScreener alone does
 * roughly (candidates x applicable queries) HypoPG round trips before
 * WorkloadIndexEvaluator's real DDL cycles even start (see
 * SCALABILITY_BENCHMARK.md) — an unbounded query count or an unbounded
 * candidate set (many small queries touching many distinct columns) both
 * turn into a request that never returns, tying up a HikariCP connection
 * the whole time.
 *
 * Deliberately generous relative to this project's own sample workloads
 * (the largest, platform-wide-peak-traffic.txt, is 100 queries generating
 * dozens of candidates) — these exist for the pathological/adversarial
 * case, not to constrain legitimate use.
 */
public final class ApiLimits {

    public static final int MAX_WORKLOAD_QUERIES = 200;
    public static final int MAX_CANDIDATES = 150;
    public static final int MAX_SQL_LENGTH = 5000;

    private ApiLimits() {}
}
