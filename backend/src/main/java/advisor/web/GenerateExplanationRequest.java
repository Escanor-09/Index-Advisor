package advisor.web;

public record GenerateExplanationRequest(String query, String bestIndex, double improvementPercent) {}
