package advisor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Standalone benchmark, not a JUnit test: measures the real write-side cost
 * of a recommended index — INSERT/UPDATE/DELETE overhead — the one
 * dimension this project has never measured before. Recommendation.tradeoffs'
 * write/maintenance line has always literally said "not measured" rather
 * than implying otherwise; this produces the real number that line was
 * missing. Targets products(category_id), the project's single most-
 * referenced candidate throughout (92-99% read improvement in every prior
 * measurement). Run with:
 *
 *   mvn exec:java -Dexec.mainClass=advisor.WriteOverheadBenchmark
 *
 * Every timed statement runs inside a transaction that is rolled back
 * immediately afterward — real Postgres write-path cost (WAL, index page
 * maintenance) is genuinely paid and measured, but the actual dataset is
 * left byte-for-byte unchanged afterward, consistent with this project's
 * "leave no residue" discipline for every other real-DDL experiment
 * (IndexEvaluator's own CREATE/DROP cycle, WorkloadBenchmark, etc.).
 *
 * Two real measurement biases were found and fixed while building this,
 * both the same class of bug already found once before on the read side
 * (IndexEvaluator.measureStable()'s cache-warming fix):
 *
 * 1. CREATE INDEX does a full sequential scan, warming the OS/shared_buffers
 *    cache — left uncorrected, the "with index" phase (which always runs
 *    after CREATE INDEX) looks faster purely from that incidental warm-up.
 *    Fixed by running an equivalent full-table warm-up scan before the
 *    baseline phase too.
 * 2. JVM JIT compilation only reaches steady state after many invocations —
 *    whichever phase runs second always looks faster purely from more total
 *    JIT-compiled invocations by that point, independent of the index. This
 *    bias persisted even after fixing #1. Fixed with a large (300-iteration)
 *    JIT warm-up before *either* phase begins.
 *
 * Even after both fixes, at this project's current 10,000-row scale a
 * single narrow index's real per-statement maintenance cost is small enough
 * (tens of microseconds) to sit inside the noise floor of client-side JDBC
 * round-trip timing on a single local machine — repeated trials sometimes
 * show positive overhead, sometimes negative. Rather than report one
 * cherry-picked run, this runs TRIALS full experiments and reports both the
 * per-trial breakdown and the aggregate, so that variance is visible
 * instead of hidden — consistent with this project's disclosed-limitation
 * discipline everywhere else (see SCALABILITY_BENCHMARK.md).
 */
public class WriteOverheadBenchmark {

    private static final String URL = "jdbc:postgresql://localhost:5432/ecom_test";
    private static final String INDEX_NAME = "idx_write_overhead_bench_category_id";
    private static final int WARMUP_RUNS = 5;
    private static final int MEASURED_RUNS = 41;
    private static final int JIT_WARMUP_ITERATIONS = 300;
    private static final int TRIALS = 5;

    /** Returns the elapsed nanoseconds of just the portion being measured — lets an op (like
     *  DELETE, below) do untimed setup work first without polluting the timed sample. */
    private interface Op {
        long run(Connection conn, long categoryId, long productId) throws SQLException;
    }

    private record TrialResult(long baselineInsertUs, long indexedInsertUs,
                                long baselineUpdateUs, long indexedUpdateUs,
                                long baselineDeleteUs, long indexedDeleteUs) {
    }

    public static void main(String[] args) throws Exception {
        String user = System.getProperty("user.name");

        try (Connection conn = DriverManager.getConnection(URL, user, "")) {
            conn.setAutoCommit(false);

            long categoryId = fetchScalarLong(conn, "SELECT id FROM categories ORDER BY id LIMIT 1;");
            long productId = fetchScalarLong(conn, "SELECT id FROM products ORDER BY id LIMIT 1;");

            System.out.println("Target: products(category_id) — sample category_id=" + categoryId
                + ", sample product id=" + productId);
            System.out.println();

            dropIndexIfExists(conn);
            conn.commit();

            System.out.println("JIT warm-up (" + JIT_WARMUP_ITERATIONS + " unmeasured iterations, "
                + "run once before any measured phase)...");
            preWarmJit(conn, categoryId, productId);

            List<TrialResult> trials = new ArrayList<>();
            for (int t = 1; t <= TRIALS; t++) {
                System.out.println("Trial " + t + "/" + TRIALS + "...");
                trials.add(runTrial(conn, categoryId, productId));
            }

            System.out.println();
            printPerTrial(trials);
            System.out.println();
            printAggregate(trials);
        }
    }

    private static TrialResult runTrial(Connection conn, long categoryId, long productId) throws SQLException {
        dropIndexIfExists(conn);
        conn.commit();

        // Full-table scan, matching what CREATE INDEX below does — removes the page-cache
        // warm-up asymmetry between the baseline and indexed phases (see class doc, bias #1).
        warmCache(conn);

        long baselineInsert = measure(conn, WriteOverheadBenchmark::insertRow, categoryId, productId);
        long baselineUpdate = measure(conn, WriteOverheadBenchmark::updateCategoryId, categoryId, productId);
        long baselineDelete = measure(conn, WriteOverheadBenchmark::deleteRow, categoryId, productId);

        createIndex(conn);
        conn.commit();

        long indexedInsert = measure(conn, WriteOverheadBenchmark::insertRow, categoryId, productId);
        long indexedUpdate = measure(conn, WriteOverheadBenchmark::updateCategoryId, categoryId, productId);
        long indexedDelete = measure(conn, WriteOverheadBenchmark::deleteRow, categoryId, productId);

        dropIndexIfExists(conn);
        conn.commit();

        return new TrialResult(baselineInsert, indexedInsert, baselineUpdate, indexedUpdate,
            baselineDelete, indexedDelete);
    }

    private static long measure(Connection conn, Op op, long categoryId, long productId) throws SQLException {
        for (int i = 0; i < WARMUP_RUNS; i++) {
            op.run(conn, categoryId, productId);
            conn.rollback();
        }

        List<Long> samplesMicros = new ArrayList<>();
        for (int i = 0; i < MEASURED_RUNS; i++) {
            long elapsedNanos = op.run(conn, categoryId, productId);
            conn.rollback();
            samplesMicros.add(elapsedNanos / 1_000);
        }

        Collections.sort(samplesMicros);
        return samplesMicros.get(samplesMicros.size() / 2);
    }

    private static void printPerTrial(List<TrialResult> trials) {
        System.out.printf("%-8s %-28s %14s %14s %10s%n", "Trial", "Operation", "Baseline(us)", "Indexed(us)", "Overhead");
        System.out.println("-".repeat(80));
        for (int i = 0; i < trials.size(); i++) {
            TrialResult t = trials.get(i);
            printRow(i + 1, "INSERT", t.baselineInsertUs(), t.indexedInsertUs());
            printRow(i + 1, "UPDATE (indexed column)", t.baselineUpdateUs(), t.indexedUpdateUs());
            printRow(i + 1, "DELETE", t.baselineDeleteUs(), t.indexedDeleteUs());
        }
    }

    private static void printRow(int trial, String label, long baselineUs, long indexedUs) {
        double overheadPercent = baselineUs == 0 ? 0.0 : ((indexedUs - baselineUs) * 100.0) / baselineUs;
        System.out.printf("%-8d %-28s %14d %14d %9.1f%%%n", trial, label, baselineUs, indexedUs, overheadPercent);
    }

    private static void printAggregate(List<TrialResult> trials) {
        System.out.println("Aggregate across " + trials.size() + " trials (mean of trial medians):");
        System.out.printf("%-28s %14s %14s %10s%n", "Operation", "Baseline(us)", "Indexed(us)", "Overhead");
        System.out.println("-".repeat(72));
        printAggregateRow("INSERT", trials.stream().mapToLong(TrialResult::baselineInsertUs).average().orElse(0),
            trials.stream().mapToLong(TrialResult::indexedInsertUs).average().orElse(0));
        printAggregateRow("UPDATE (indexed column)", trials.stream().mapToLong(TrialResult::baselineUpdateUs).average().orElse(0),
            trials.stream().mapToLong(TrialResult::indexedUpdateUs).average().orElse(0));
        printAggregateRow("DELETE", trials.stream().mapToLong(TrialResult::baselineDeleteUs).average().orElse(0),
            trials.stream().mapToLong(TrialResult::indexedDeleteUs).average().orElse(0));
    }

    private static void printAggregateRow(String label, double baselineUs, double indexedUs) {
        double overheadPercent = baselineUs == 0 ? 0.0 : ((indexedUs - baselineUs) * 100.0) / baselineUs;
        System.out.printf("%-28s %14.1f %14.1f %9.1f%%%n", label, baselineUs, indexedUs, overheadPercent);
    }

    private static long insertRow(Connection conn, long categoryId, long productId) throws SQLException {
        String sql = "INSERT INTO products (category_id, name, description, price, stock, rating, brand, "
            + "created_at, status) VALUES (?, 'bench-temp', 'write-overhead benchmark row', 9.99, 1, 4.0, "
            + "'bench', now(), 'active')";

        long start = System.nanoTime();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, categoryId);
            stmt.executeUpdate();
        }
        return System.nanoTime() - start;
    }

    private static long updateCategoryId(Connection conn, long categoryId, long productId) throws SQLException {
        // Same value in, same value declared — Postgres still walks the index to maintain it on
        // an UPDATE of an indexed column regardless of whether the new value differs, so this is
        // a legitimate measurement of index-maintenance cost, not a no-op the planner can skip.
        String sql = "UPDATE products SET category_id = ? WHERE id = ?";

        long start = System.nanoTime();
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setLong(1, categoryId);
            stmt.setLong(2, productId);
            stmt.executeUpdate();
        }
        return System.nanoTime() - start;
    }

    /**
     * Deletes a row inserted moments earlier in this same open transaction, not an existing real
     * product — real rows can be referenced by order_items (fk_order_items_product), which would
     * make a real DELETE fail with a foreign-key violation rather than measure anything. The
     * INSERT setup is deliberately untimed; only the DELETE itself is measured.
     */
    private static long deleteRow(Connection conn, long categoryId, long productId) throws SQLException {
        long tempId;
        String insertSql = "INSERT INTO products (category_id, name, description, price, stock, rating, "
            + "brand, created_at, status) VALUES (?, 'bench-temp', 'write-overhead benchmark row', 9.99, 1, "
            + "4.0, 'bench', now(), 'active') RETURNING id";

        try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
            stmt.setLong(1, categoryId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                tempId = rs.getLong(1);
            }
        }

        String deleteSql = "DELETE FROM products WHERE id = ?";
        long start = System.nanoTime();
        try (PreparedStatement stmt = conn.prepareStatement(deleteSql)) {
            stmt.setLong(1, tempId);
            stmt.executeUpdate();
        }
        return System.nanoTime() - start;
    }

    private static void preWarmJit(Connection conn, long categoryId, long productId) throws SQLException {
        for (int i = 0; i < JIT_WARMUP_ITERATIONS; i++) {
            insertRow(conn, categoryId, productId);
            conn.rollback();
            updateCategoryId(conn, categoryId, productId);
            conn.rollback();
            deleteRow(conn, categoryId, productId);
            conn.rollback();
        }
    }

    private static void warmCache(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT count(*) FROM products;");
        }
        conn.commit();
    }

    private static void createIndex(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE INDEX " + INDEX_NAME + " ON products (category_id);");
        }
    }

    private static void dropIndexIfExists(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP INDEX IF EXISTS " + INDEX_NAME + ";");
        }
    }

    private static long fetchScalarLong(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            rs.next();
            long value = rs.getLong(1);
            conn.commit();
            return value;
        }
    }
}
