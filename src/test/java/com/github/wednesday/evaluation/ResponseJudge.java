package com.github.wednesday.evaluation;

/**
 * A judge that evaluates Wednesday responses against system prompt rules.
 * Uses an LLM to perform structured evaluation (LLM-as-a-Judge pattern).
 */
public interface ResponseJudge {
    
    /**
     * Evaluate a turn (user message + assistant response).
     *
     * @param userMessage      The user's input
     * @param assistantResponse Wednesday's response
     * @param previousContext  Previous turn for language-matching evaluation (optional)
     * @return Evaluation result with scores per criterion
     */
    EvaluationResult evaluate(
        String userMessage,
        String assistantResponse,
        String previousContext
    );
    
    /**
     * Evaluate without context (for simple standalone turns).
     */
    default EvaluationResult evaluate(String userMessage, String assistantResponse) {
        return evaluate(userMessage, assistantResponse, null);
    }
}
