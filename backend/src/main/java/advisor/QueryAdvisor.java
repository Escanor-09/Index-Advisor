package advisor;

import java.util.List;

import advisor.candidates.CandidateGenerator;
import advisor.candidates.CandidateIndex;
import advisor.candidates.ExistingIndex;
import advisor.candidates.ExistingIndexManager;
import advisor.database.PostgresManager;
import advisor.evaluation.EvaluationResult;
import advisor.evaluation.IndexEvaluator;
import advisor.parsing.ParsedQuery;
import advisor.parsing.SqlParser;
import advisor.recommendation.Recommendation;
import advisor.recommendation.RecommendationEngine;

/**
 * Top-level single-query orchestrator:
 * parse -> generate candidates -> drop candidates already covered by a real
 * index -> evaluate what's left -> recommend.
 */
public class QueryAdvisor {

    private final IndexEvaluator evaluator;
    private final ExistingIndexManager existingIndexManager;

    public QueryAdvisor(PostgresManager db) {
        this.evaluator = new IndexEvaluator(db);
        this.existingIndexManager = new ExistingIndexManager(db);
    }

    public List<Recommendation> analyze(String sql) {
        return analyzeDetailed(sql).recommendations();
    }

    /** Exposes the full parsed query and every evaluated candidate, not just the final filtered recommendations — the HTTP layer needs this to render query analysis and a full candidate table. */
    public SingleQueryAnalysis analyzeDetailed(String sql) {
        validateSql(sql);

        ParsedQuery parsed = SqlParser.parse(sql);
        List<CandidateIndex> candidates = CandidateGenerator.generate(parsed);

        List<ExistingIndex> existingIndexes = existingIndexManager.getExistingIndexes();

        List<CandidateIndex> candidatesToEvaluate = candidates.stream()
            .filter(candidate -> !existingIndexManager.isCoveredByExistingIndex(candidate, existingIndexes))
            .toList();

        List<EvaluationResult> results = candidatesToEvaluate.stream()
            .map(candidate -> evaluator.evaluate(candidate, sql))
            .toList();

        List<Recommendation> recommendations = RecommendationEngine.recommend(results, sql);

        return new SingleQueryAnalysis(parsed, results, recommendations);
    }

    public record SingleQueryAnalysis(
        ParsedQuery parsedQuery,
        List<EvaluationResult> allResults,
        List<Recommendation> recommendations
    ) {}

    /** Package-private (not private) so it's directly unit-testable — no PostgresManager needed. Rejects fast, before SqlParser or any database work runs. See ApiLimits. */
    static void validateSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("Query must not be empty.");
        }

        if (sql.length() > ApiLimits.MAX_SQL_LENGTH) {
            throw new IllegalArgumentException(
                "Query is " + sql.length() + " characters, exceeding the maximum of "
                    + ApiLimits.MAX_SQL_LENGTH + "."
            );
        }
    }
}
