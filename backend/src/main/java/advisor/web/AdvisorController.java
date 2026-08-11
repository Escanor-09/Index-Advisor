package advisor.web;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import advisor.QueryAdvisor;
import advisor.WorkloadQueryAdvisor;
import advisor.database.PostgresManager;
import advisor.recommendation.Recommendation;

@RestController
public class AdvisorController {

    private final QueryAdvisor queryAdvisor;
    private final WorkloadQueryAdvisor workloadQueryAdvisor;

    // Built once, as singleton fields, when Spring creates this controller bean at startup —
    // so IndexEvaluator's database-safety check and schema-allowlist load also happen once,
    // not per request.
    public AdvisorController(PostgresManager db) {
        this.queryAdvisor = new QueryAdvisor(db);
        this.workloadQueryAdvisor = new WorkloadQueryAdvisor(db);
    }

    @PostMapping("/analyze")
    public List<Recommendation> analyze(@RequestBody AnalyzeRequest request) {
        return queryAdvisor.analyze(request.query());
    }

    @PostMapping("/analyze-workload")
    public List<Recommendation> analyzeWorkload(@RequestBody AnalyzeWorkloadRequest request) {
        return workloadQueryAdvisor.analyzeWorkload(request.queries());
    }

    // SqlParser rejects non-SELECT / multi-table / unsupported queries this way, and
    // IndexEvaluator rejects any table/column not found in the real schema this way too.
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(new ErrorResponse("Unexpected error: " + e.getMessage()));
    }
}
