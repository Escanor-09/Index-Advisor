package advisor;

import java.nio.file.Path;
import java.util.List;

import advisor.candidates.CandidateGenerator;
import advisor.candidates.CandidateIndex;
import advisor.database.PostgresManager;
import advisor.evaluation.EvaluationResult;
import advisor.evaluation.HypoEvaluationResult;
import advisor.evaluation.HypoIndexEvaluator;
import advisor.evaluation.IndexEvaluator;
import advisor.parsing.ParsedQuery;
import advisor.parsing.SqlParser;
import advisor.planning.QueryPlan;
import advisor.recommendation.Recommendation;
import advisor.workload.WorkloadAdvisor;
import advisor.workload.WorkloadProfile;
import advisor.workload.WorkloadQuery;
import advisor.workload.WorkloadReader;

public class Main {

    public static void main(String[] args) {
        runSqlParserChecks();

        String host = env("DB_HOST", "localhost");
        String port = env("DB_PORT", "5432");
        String dbName = env("DB_NAME", "index_advisor");
        String user = env("DB_USER", System.getProperty("user.name"));
        String password = env("DB_PASSWORD", "");

        String url = "jdbc:postgresql://" + host + ":" + port + "/" + dbName;

        System.out.println("Connecting to " + url + " as " + user + "...");

        PostgresManager db = new PostgresManager(url, user, password);

        if (!db.testConnection()) {
            System.out.println("Connection FAILED");
            db.close();
            return;
        }

        System.out.println("Connection OK\n");

        String count = db.executeScalar("SELECT COUNT(*) FROM categories;");
        System.out.println("Categories: " + count);

        String plan = db.explainAnalyze("SELECT * FROM categories WHERE id = 5;");
        System.out.println("\nEXPLAIN (ANALYZE, BUFFERS, FORMAT JSON):");
        System.out.println(plan);

        List<String> queries = WorkloadReader.readQueries(Path.of("workload/queries.sql"));

        runWorkloadDemo(db, queries);

        WorkloadProfile profile = runWorkloadProfiling(db, queries);

        runCandidateGeneration(profile);

        runIndexEvaluation(db, profile);

        runHypoPgDemo(db, profile);

        runJoinSupportDemo(db);

        runQueryAdvisorDemo(db, queries);

        runWorkloadAdvisorDemo(db, queries);

        db.close();
    }

    private static void runWorkloadDemo(PostgresManager db, List<String> queries) {
        System.out.println("\n\nWorkload ingestion (Milestone 7) + parsing + plan collection:\n");

        System.out.println("Loaded " + queries.size() + " queries from workload/queries.sql\n");

        int i = 1;

        for (String sql : queries) {
            ParsedQuery parsed = SqlParser.parse(sql);
            String explainJson = db.explainAnalyze(sql);
            QueryPlan queryPlan = new QueryPlan(explainJson);

            System.out.printf(
                "Q%d %s%n  table=%s filterColumns=%s%n  nodeType=%s totalCost=%.2f executionTime=%.3fms%n%n",
                i++,
                sql,
                parsed.tableName(),
                parsed.filterColumns(),
                queryPlan.getNodeType(),
                queryPlan.getTotalCost(),
                queryPlan.getExecutionTime()
            );
        }
    }

    private static WorkloadProfile runWorkloadProfiling(PostgresManager db, List<String> queries) {
        System.out.println("\nWorkload profiling (Milestone 8):\n");

        WorkloadProfile profile = WorkloadAdvisor.analyzeWorkload(queries, db);
        System.out.println(profile.report());

        return profile;
    }

    private static void runCandidateGeneration(WorkloadProfile profile) {
        System.out.println("\nCandidate generation (Milestone 9):\n");

        WorkloadQuery multiColumnQuery = firstMultiColumnQuery(profile);

        System.out.println("For: " + multiColumnQuery.sql());

        List<CandidateIndex> candidates = CandidateGenerator.generate(multiColumnQuery.parsedQuery());
        candidates.forEach(c -> System.out.println("  " + c));

        List<ParsedQuery> allParsed = profile.queries().stream().map(WorkloadQuery::parsedQuery).toList();
        List<CandidateIndex> workloadCandidates = CandidateGenerator.generateForWorkload(allParsed);

        System.out.println("\nTotal distinct candidates across whole workload: " + workloadCandidates.size());
        workloadCandidates.forEach(c -> System.out.println("  " + c));
    }

    private static void runIndexEvaluation(PostgresManager db, WorkloadProfile profile) {
        System.out.println("\nIndex evaluation (Milestone 10):\n");

        WorkloadQuery multiColumnQuery = firstMultiColumnQuery(profile);
        List<CandidateIndex> candidates = CandidateGenerator.generate(multiColumnQuery.parsedQuery());

        IndexEvaluator evaluator = new IndexEvaluator(db);

        System.out.println("Query: " + multiColumnQuery.sql() + "\n");

        for (CandidateIndex candidate : candidates) {
            EvaluationResult result = evaluator.evaluate(candidate, multiColumnQuery.sql());

            System.out.printf(
                "  %-32s %s -> %-14s cost %.2f -> %.2f   time %.3fms -> %.3fms   improvement %.1f%%%n",
                candidate,
                result.beforeNodeType(),
                result.afterNodeType(),
                result.beforeCost(),
                result.afterCost(),
                result.beforeExecutionTime(),
                result.afterExecutionTime(),
                result.improvementPercent()
            );
        }
    }

    private static void runHypoPgDemo(PostgresManager db, WorkloadProfile profile) {
        System.out.println("\nHypoPG screening (Priority Improvement) — hypothetical indexes, no real DDL:\n");

        WorkloadQuery multiColumnQuery = firstMultiColumnQuery(profile);
        List<CandidateIndex> candidates = CandidateGenerator.generate(multiColumnQuery.parsedQuery());

        HypoIndexEvaluator hypoEvaluator = new HypoIndexEvaluator(db);
        IndexEvaluator realEvaluator = new IndexEvaluator(db);

        System.out.println("Query: " + multiColumnQuery.sql() + "\n");
        System.out.println("Cross-validating HypoPG's estimated-cost screening against IndexEvaluator's real measurement:\n");

        for (CandidateIndex candidate : candidates) {
            HypoEvaluationResult hypo = hypoEvaluator.evaluate(candidate, multiColumnQuery.sql());
            EvaluationResult real = realEvaluator.evaluate(candidate, multiColumnQuery.sql());

            System.out.printf(
                "  %-32s HypoPG: %s -> %-16s cost %.2f -> %.2f (%.1f%% est.)%n",
                candidate,
                hypo.beforeNodeType(),
                hypo.afterNodeType(),
                hypo.beforeCost(),
                hypo.afterCost(),
                hypo.estimatedCostImprovementPercent()
            );
            System.out.printf(
                "  %-32s Real:   %s -> %-16s time %.3fms -> %.3fms (%.1f%% measured)%n%n",
                "",
                real.beforeNodeType(),
                real.afterNodeType(),
                real.beforeExecutionTime(),
                real.afterExecutionTime(),
                real.improvementPercent()
            );
        }
    }

    private static void runJoinSupportDemo(PostgresManager db) {
        System.out.println("\nJOIN support (Priority Improvement) — real queries, real database, real join columns:\n");

        // Hash Join: orders is fully scanned regardless (no selective filter on it), customers.status
        // matches ~59% of rows (not selective) — genuinely no candidate here should help. Verified by
        // hand via EXPLAIN ANALYZE before trusting this as a "the feature works" result, not a bug.
        runJoinQuery(
            db,
            "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id WHERE c.status = 'active';"
        );

        // Nested Loop: customers.id = 500 is maximally selective (uses the existing PK index as outer),
        // but orders has no index on customer_id, so the inner side Seq Scans all 10,000 rows per
        // outer match. This is the textbook case join-column indexing exists for.
        runJoinQuery(
            db,
            "SELECT * FROM customers c JOIN orders o ON c.id = o.customer_id WHERE c.id = 500;"
        );
    }

    private static void runJoinQuery(PostgresManager db, String sql) {
        ParsedQuery parsed = SqlParser.parse(sql);
        System.out.println("Query: " + sql);
        System.out.println("  primary table:     " + parsed.tableName());
        System.out.println("  joins:              " + parsed.joins());
        System.out.println("  qualified filters:  " + parsed.qualifiedFilterColumns());

        List<CandidateIndex> candidates = CandidateGenerator.generate(parsed);
        System.out.println("  candidates:         " + candidates + "\n");

        QueryAdvisor advisor = new QueryAdvisor(db);
        List<Recommendation> recommendations = advisor.analyze(sql);

        if (recommendations.isEmpty()) {
            System.out.println("  No recommendation — no candidate cleared the minimum improvement threshold.\n");
            return;
        }

        int i = 1;

        for (Recommendation r : recommendations) {
            System.out.println(r.report(i++));
        }
    }

    private static void runQueryAdvisorDemo(PostgresManager db, List<String> queries) {
        System.out.println(
            "\nSingle-query advisor (Milestone 12) "
                + "— full pipeline: parse -> candidates -> evaluate -> recommend:\n"
        );

        QueryAdvisor advisor = new QueryAdvisor(db);

        // Q1 (single, selective column), Q4 (composite), Q6 (single, low-selectivity column).
        List<String> sample = List.of(queries.get(0), queries.get(3), queries.get(5));

        for (String sql : sample) {
            System.out.println("Query: " + sql);

            List<Recommendation> recommendations = advisor.analyze(sql);

            if (recommendations.isEmpty()) {
                System.out.println("  No recommendation — no candidate cleared the minimum improvement threshold.\n");
                continue;
            }

            int i = 1;

            for (Recommendation r : recommendations) {
                System.out.println(r.report(i++));
            }
        }
    }

    private static void runWorkloadAdvisorDemo(PostgresManager db, List<String> queries) {
        System.out.println(
            "\nWorkload advisor (Milestone 13) "
                + "— evaluate every candidate across the whole workload, choose an index SET:\n"
        );

        WorkloadQueryAdvisor workloadAdvisor = new WorkloadQueryAdvisor(db);
        List<Recommendation> recommendations = workloadAdvisor.analyzeWorkload(queries);

        System.out.println("Chosen index set (" + recommendations.size() + " indexes):\n");

        int i = 1;

        for (Recommendation r : recommendations) {
            System.out.println(r.report(i++));
        }
    }

    private static WorkloadQuery firstMultiColumnQuery(WorkloadProfile profile) {
        return profile.queries().stream()
            .filter(wq -> wq.parsedQuery().filterColumns().size() > 1)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No multi-column-filter query found in workload."));
    }

    private static void runSqlParserChecks() {
        String[] queries = {
            "SELECT * FROM categories WHERE id = 5;",
            "SELECT * FROM products WHERE category_id = 5 AND price > 100;",
            "SELECT * FROM orders WHERE (status = 'delivered' OR status = 'shipped') AND customer_id = 500;",
            "SELECT * FROM products;"
        };

        System.out.println("SQL Parser checks:\n");

        for (String sql : queries) {
            ParsedQuery parsed = SqlParser.parse(sql);
            System.out.println(sql);
            System.out.println("  table:         " + parsed.tableName());
            System.out.println("  filterColumns: " + parsed.filterColumns());
            System.out.println();
        }
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isEmpty()) ? fallback : value;
    }
}
