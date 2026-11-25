package com.github.wednesday.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLM-as-a-Judge evaluation suite for Wednesday.
 * 
 * Tests Wednesday's responses against system prompt rules using
 * Gemini as an evaluator.
 * 
 * Requirements:
 * - Set GEMINI_API_KEY environment variable
 * - Optionally set WEDNESDAY_EVAL_OUTPUT_DIR for reports
 * 
 * Usage:
 *   mvn test -Dtest=WednesdayEvaluationSuite
 *   
 * This generates:
 * - Console output with detailed results
 * - evaluation-report-{timestamp}.txt in output dir
 * - evaluation-report-{timestamp}.csv for analysis
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class WednesdayEvaluationSuite {
    
    private static final String OUTPUT_DIR = System.getenv().getOrDefault(
        "WEDNESDAY_EVAL_OUTPUT_DIR",
        "target/evaluation-reports"
    );
    
    @Test
    void evaluateGoodResponses() throws Exception {
        ResponseJudge judge = new GeminiJudge(System.getenv("GEMINI_API_KEY"));
        List<EvaluationResult> results = new ArrayList<>();
        
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  WEDNESDAY EVALUATION SUITE - GOOD RESPONSES         ║");
        System.out.println("╚══════════════════════════════════════════════════════╝\n");
        
        for (TestCases.TestCase testCase : TestCases.ALL) {
            System.out.printf("Running %s: %s%n", testCase.id(), testCase.description());
            
            EvaluationResult result = judge.evaluate(
                testCase.userMessage(),
                testCase.goodResponse()
            );
            
            results.add(result);
            
            System.out.printf("  Score: %.2f | Passed: %d/%d%n",
                result.overallScore(),
                result.passedCount(),
                result.totalCriteria());
            
            // Print any failed criteria
            result.scores().entrySet().stream()
                .filter(e -> !e.getValue().passed())
                .forEach(e -> System.out.printf("    ⚠ %s failed: %s%n",
                    e.getKey().displayName(),
                    e.getValue().reasoning()));
            
            System.out.println();
            
            // Add small delay to avoid rate limits
            Thread.sleep(1000);
        }
        
        // Generate and save reports
        String textReport = EvaluationReport.formatSuite(results);
        String csvReport = EvaluationReport.formatCSV(results);
        
        System.out.println(textReport);
        
        saveReports(textReport, csvReport);
        
        // Assertions
        double avgScore = results.stream()
            .mapToDouble(EvaluationResult::overallScore)
            .average()
            .orElse(0.0);
        
        assertTrue(avgScore >= 0.7,
            String.format("Average score %.2f below threshold 0.7", avgScore));
        
        long passedAll = results.stream()
            .filter(EvaluationResult::passesAll)
            .count();
        
        assertTrue(passedAll >= results.size() * 0.8,
            String.format("Only %d/%d responses passed all criteria", passedAll, results.size()));
    }
    
    @Test
    void evaluateBadResponses() throws Exception {
        ResponseJudge judge = new GeminiJudge(System.getenv("GEMINI_API_KEY"));
        List<EvaluationResult> results = new ArrayList<>();
        
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  WEDNESDAY EVALUATION SUITE - BAD RESPONSES          ║");
        System.out.println("╚══════════════════════════════════════════════════════╝\n");
        
        for (TestCases.TestCase testCase : TestCases.ALL) {
            System.out.printf("Running %s: %s%n", testCase.id(), testCase.description());
            
            EvaluationResult result = judge.evaluate(
                testCase.userMessage(),
                testCase.badResponse()
            );
            
            results.add(result);
            
            System.out.printf("  Score: %.2f | Passed: %d/%d%n",
                result.overallScore(),
                result.passedCount(),
                result.totalCriteria());
            
            // Should fail on primary criteria
            for (EvaluationCriteria criterion : testCase.primaryCriteria()) {
                EvaluationResult.CriterionScore score = result.scores().get(criterion);
                if (!score.passed()) {
                    System.out.printf("  ✓ Correctly detected %s violation%n", criterion.displayName());
                } else {
                    System.out.printf("  ⚠ Missed %s violation (score: %.2f)%n",
                        criterion.displayName(), score.score());
                }
            }
            
            System.out.println();
            
            Thread.sleep(1000);
        }
        
        // Bad responses should have lower scores
        double avgScore = results.stream()
            .mapToDouble(EvaluationResult::overallScore)
            .average()
            .orElse(1.0);
        
        assertTrue(avgScore < 0.7,
            String.format("Bad responses scored too high: %.2f", avgScore));
        
        System.out.println("\nBad responses correctly identified with avg score: " + String.format("%.2f", avgScore));
    }
    
    @Test
    void evaluateSingleTurn() {
        ResponseJudge judge = new GeminiJudge(System.getenv("GEMINI_API_KEY"));
        
        // Manual test case
        String userMessage = "¿Qué hora es?";
        String assistantResponse = "Son las tres y veinte de la tarde.";
        
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  SINGLE TURN EVALUATION                              ║");
        System.out.println("╚══════════════════════════════════════════════════════╝\n");
        
        EvaluationResult result = judge.evaluate(userMessage, assistantResponse);
        
        String report = EvaluationReport.formatSingle(result);
        System.out.println(report);
        
        assertTrue(result.overallScore() >= 0.7,
            "Response should score above 0.7");
    }
    
    private void saveReports(String textReport, String csvReport) throws Exception {
        Path outputPath = Path.of(OUTPUT_DIR);
        Files.createDirectories(outputPath);
        
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        
        Path textFile = outputPath.resolve(String.format("evaluation-report-%s.txt", timestamp));
        Path csvFile = outputPath.resolve(String.format("evaluation-report-%s.csv", timestamp));
        
        Files.writeString(textFile, textReport);
        Files.writeString(csvFile, csvReport);
        
        System.out.printf("\n📊 Reports saved:%n");
        System.out.printf("   Text: %s%n", textFile.toAbsolutePath());
        System.out.printf("   CSV:  %s%n", csvFile.toAbsolutePath());
    }
}
