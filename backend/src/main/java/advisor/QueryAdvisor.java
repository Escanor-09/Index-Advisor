package advisor;

import java.util.List;

import advisor.candidates.CandidateGenerator;
import advisor.candidates.CandidateIndex;
import advisor.database.PostgresManager;
import advisor.evaluation.EvaluationResult;
import advisor.evaluation.IndexEvaluator;
import advisor.parsing.ParsedQuery;
import advisor.parsing.SqlParser;
import advisor.recommendation.Recommendation;
import advisor.recommendation.RecommendationEngine;

/**
 * Top-level single-query orchestrator:
 * parse -> generate candidates -> evaluate -> recommend.
 */
public class QueryAdvisor {

    private final IndexEvaluator evaluator;

    public QueryAdvisor(PostgresManager db) {
        this.evaluator = new IndexEvaluator(db);
    }

    public List<Recommendation> analyze(String sql) {
        ParsedQuery parsed = SqlParser.parse(sql);
        List<CandidateIndex> candidates = CandidateGenerator.generate(parsed);

        List<EvaluationResult> results = candidates.stream()
            .map(candidate -> evaluator.evaluate(candidate, sql))
            .toList();

        return RecommendationEngine.recommend(results, sql);
    }
}
