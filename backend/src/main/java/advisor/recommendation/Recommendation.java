package advisor.recommendation;

import java.util.List;

import advisor.candidates.CandidateIndex;

public record Recommendation(
    CandidateIndex candidate,
    String impact, // HIGH | MEDIUM | LOW
    List<String> affectedQueries,
    String reason,
    String observedEffect, // e.g. "Seq Scan -> Bitmap Heap Scan"
    double estimatedImprovementPercent,
    long indexSizeBytes, // real, measured via pg_relation_size while the index existed
    List<String> tradeoffs
) {

    public String report(int index) {
        return String.format(
            """
            Recommendation #%d

            Index:
                %s

            Expected impact:
                %s

            Queries affected:
                %s

            Reason:
                %s

            Observed effect:
                %s

            Estimated improvement:
                %.1f%%

            Index size:
                %s

            Trade-off:
                %s
            """,
            index,
            candidate,
            impact,
            String.join(", ", affectedQueries),
            reason,
            observedEffect,
            estimatedImprovementPercent,
            formatBytes(indexSizeBytes),
            String.join("\n    ", tradeoffs)
        );
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
