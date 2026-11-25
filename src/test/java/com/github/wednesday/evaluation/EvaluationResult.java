package com.github.wednesday.evaluation;

import java.util.Map;
import java.util.Objects;

/**
 * Result of evaluating a Wednesday response against all criteria.
 */
public record EvaluationResult(
    String userMessage,
    String assistantResponse,
    Map<EvaluationCriteria, CriterionScore> scores,
    double overallScore,
    String summary
) {
    
    public EvaluationResult {
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(assistantResponse, "assistantResponse");
        Objects.requireNonNull(scores, "scores");
        Objects.requireNonNull(summary, "summary");
        
        if (overallScore < 0.0 || overallScore > 1.0) {
            throw new IllegalArgumentException("overallScore must be between 0.0 and 1.0: " + overallScore);
        }
    }
    
    /**
     * Score for a single evaluation criterion.
     */
    public record CriterionScore(
        double score,         // 0.0 to 1.0
        boolean passed,       // true if score >= threshold (0.7)
        String reasoning,     // Judge's explanation
        String evidence       // Specific text that passed/failed
    ) {
        private static final double PASS_THRESHOLD = 0.7;
        
        public CriterionScore {
            Objects.requireNonNull(reasoning, "reasoning");
            if (score < 0.0 || score > 1.0) {
                throw new IllegalArgumentException("score must be between 0.0 and 1.0: " + score);
            }
        }
        
        public static CriterionScore of(double score, String reasoning, String evidence) {
            return new CriterionScore(score, score >= PASS_THRESHOLD, reasoning, evidence);
        }
    }
    
    /**
     * Check if the response passes all criteria.
     */
    public boolean passesAll() {
        return scores.values().stream().allMatch(CriterionScore::passed);
    }
    
    /**
     * Count how many criteria passed.
     */
    public long passedCount() {
        return scores.values().stream().filter(CriterionScore::passed).count();
    }
    
    /**
     * Count total criteria.
     */
    public int totalCriteria() {
        return scores.size();
    }
}
