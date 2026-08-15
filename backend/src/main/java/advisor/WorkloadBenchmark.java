package advisor;

import java.nio.file.Path;
import java.util.List;

import advisor.candidates.CandidateGenerator;
import advisor.candidates.CandidateIndex;
import advisor.database.PostgresManager;
import advisor.parsing.ParsedQuery;
import advisor.workload.WorkloadAdvisor;
import advisor.workload.WorkloadHypoScreener;
import advisor.workload.WorkloadIndexEvaluator;
import advisor.workload.WorkloadProfile;
import advisor.workload.WorkloadQuery;
import advisor.workload.WorkloadReader;

/**
 * Standalone benchmark, not a JUnit test: measures how workload analysis
 * time scales with workload size, and quantifies the real speedup HypoPG
 * screening (WorkloadHypoScreener, Priority Improvement — HypoPG Pipeline
 * Wiring) provides over evaluating every candidate with real DDL, the
 * pipeline's shape before that change. Run with:
 *
 *   mvn exec:java -Dexec.mainClass=advisor.WorkloadBenchmark
 *
 * Deliberately outside the JUnit suite: this takes real minutes to run
 * against larger workloads by design — that cost is exactly what's being
 * measured — which would make `mvn test` unacceptably slow if it ran on
 * every build.
 *
 * Caveat, disclosed rather than glossed over: "screened" and "real-only"
 * run sequentially against the same warm database within one process, not
 * in isolated cold-cache environments. "Screened" runs first deliberately
 * — the more conservative ordering, since it does the least real DDL and
 * so leaves the least residual cache warmth behind for "real-only" to
 * unfairly benefit from. The dominant cost difference measured here is real
 * CREATE/DROP INDEX cycle count, not page-cache state, so this ordering
 * choice matters at the margin, not to the headline result.
 */
public class WorkloadBenchmark {

    private record BenchmarkCase(String file, int limitQueries) {
        String label() {
            return limitQueries < 0 ? file : file + " (first " + limitQueries + ")";
        }
    }

    public static void main(String[] args) throws Exception {
        String user = System.getProperty("user.name");

        List<BenchmarkCase> cases = List.of(
            new BenchmarkCase("customer-support-lookups.txt", -1),
            new BenchmarkCase("product-catalog-browsing.txt", -1),
            new BenchmarkCase("platform-wide-peak-traffic.txt", 25),
            new BenchmarkCase("platform-wide-peak-traffic.txt", 50),
            new BenchmarkCase("platform-wide-peak-traffic.txt", 100)
        );

        System.out.printf(
            "%-45s %5s %6s %10s %14s %14s %8s%n",
            "Workload", "Qs", "Cands", "Promoted", "Screened(ms)", "Real-only(ms)", "Speedup"
        );
        System.out.println("-".repeat(110));

        try (PostgresManager db = new PostgresManager("jdbc:postgresql://localhost:5432/index_advisor", user, "")) {
            for (BenchmarkCase c : cases) {
                runCase(db, c);
            }
        }
    }

    private static void runCase(PostgresManager db, BenchmarkCase c) throws Exception {
        Path path = Path.of("workload/samples/" + c.file());
        List<String> allQueries = WorkloadReader.readQueries(path);
        List<String> queries = c.limitQueries() < 0
            ? allQueries
            : allQueries.subList(0, Math.min(c.limitQueries(), allQueries.size()));

        WorkloadProfile profile = WorkloadAdvisor.analyzeWorkload(queries, db);
        List<ParsedQuery> parsed = profile.queries().stream().map(WorkloadQuery::parsedQuery).toList();
        List<CandidateIndex> candidates = CandidateGenerator.generateForWorkload(parsed);

        // Screen-then-validate: the live pipeline as of Priority Improvement — HypoPG wiring.
        long screenedStart = System.nanoTime();
        WorkloadHypoScreener screener = new WorkloadHypoScreener(db);
        List<CandidateIndex> promoted = screener.screen(candidates, profile);
        new WorkloadIndexEvaluator(db).evaluateAll(promoted, profile);
        long screenedMs = (System.nanoTime() - screenedStart) / 1_000_000;

        // Real-DDL-only: the pipeline's shape before that change, reconstructed here for comparison.
        long realOnlyStart = System.nanoTime();
        new WorkloadIndexEvaluator(db).evaluateAll(candidates, profile);
        long realOnlyMs = (System.nanoTime() - realOnlyStart) / 1_000_000;

        double speedup = screenedMs == 0 ? 0.0 : (double) realOnlyMs / screenedMs;

        System.out.printf(
            "%-45s %5d %6d %4d/%-5d %14d %14d %7.2fx%n",
            c.label(), queries.size(), candidates.size(), promoted.size(), candidates.size(), screenedMs, realOnlyMs, speedup
        );
    }
}
