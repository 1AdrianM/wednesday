package com.github.wednesday.evaluation;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates evaluation reports in various formats.
 */
public final class EvaluationReport {
    
    private EvaluationReport() {}
    
    /**
     * Generate a detailed text report for a single evaluation.
     */
    public static String formatSingle(EvaluationResult result) {
        var sw = new StringWriter();
        var out = new PrintWriter(sw);
        
        out.println("═══════════════════════════════════════════════════════════");
        out.println("EVALUACIÓN DE RESPUESTA");
        out.println("═══════════════════════════════════════════════════════════");
        out.println();
        out.printf("Usuario: %s%n", result.userMessage());
        out.printf("Wednesday: %s%n", result.assistantResponse());
        out.println();
        out.println("───────────────────────────────────────────────────────────");
        out.println("SCORES POR CRITERIO");
        out.println("───────────────────────────────────────────────────────────");
        
        for (var entry : result.scores().entrySet()) {
            EvaluationCriteria criterion = entry.getKey();
            EvaluationResult.CriterionScore score = entry.getValue();
            
            String status = score.passed() ? "✓ PASS" : "✗ FAIL";
            out.printf("%n%s [%s] %.2f/1.0%n", criterion.displayName(), status, score.score());
            out.printf("  %s%n", score.reasoning());
            if (score.evidence() != null && !score.evidence().isBlank()) {
                out.printf("  Evidencia: \"%s\"%n", score.evidence());
            }
        }
        
        out.println();
        out.println("───────────────────────────────────────────────────────────");
        out.println("RESUMEN");
        out.println("───────────────────────────────────────────────────────────");
        out.printf("Score General: %.2f/1.0%n", result.overallScore());
        out.printf("Criterios Pasados: %d/%d%n", result.passedCount(), result.totalCriteria());
        out.printf("Estado: %s%n", result.passesAll() ? "✓ APROBADO" : "✗ NECESITA MEJORAS");
        out.println();
        out.printf("%s%n", result.summary());
        out.println();
        out.println("═══════════════════════════════════════════════════════════");
        
        return sw.toString();
    }
    
    /**
     * Generate a summary report for multiple evaluations.
     */
    public static String formatSuite(List<EvaluationResult> results) {
        var sw = new StringWriter();
        var out = new PrintWriter(sw);
        
        out.println("═══════════════════════════════════════════════════════════");
        out.println("REPORTE DE EVALUACIÓN - SUITE COMPLETA");
        out.printf("Fecha: %s%n", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        out.println("═══════════════════════════════════════════════════════════");
        out.println();
        
        // Overall stats
        double avgOverall = results.stream()
            .mapToDouble(EvaluationResult::overallScore)
            .average()
            .orElse(0.0);
        
        long totalPassed = results.stream()
            .filter(EvaluationResult::passesAll)
            .count();
        
        out.println("RESUMEN GENERAL");
        out.println("───────────────────────────────────────────────────────────");
        out.printf("Total de Evaluaciones: %d%n", results.size());
        out.printf("Aprobadas (100%%): %d (%.1f%%)%n", 
            totalPassed, 
            100.0 * totalPassed / results.size());
        out.printf("Score Promedio: %.2f/1.0%n", avgOverall);
        out.println();
        
        // Per-criterion stats
        out.println("SCORES POR CRITERIO (Promedio)");
        out.println("───────────────────────────────────────────────────────────");
        
        for (EvaluationCriteria criterion : EvaluationCriteria.values()) {
            double avg = results.stream()
                .mapToDouble(r -> r.scores().get(criterion).score())
                .average()
                .orElse(0.0);
            
            long passed = results.stream()
                .filter(r -> r.scores().get(criterion).passed())
                .count();
            
            String status = avg >= 0.7 ? "✓" : "✗";
            out.printf("%s %-25s %.2f/1.0  (%d/%d passed)%n",
                status,
                criterion.displayName() + ":",
                avg,
                passed,
                results.size());
        }
        
        out.println();
        out.println("DETALLES POR EVALUACIÓN");
        out.println("───────────────────────────────────────────────────────────");
        
        for (int i = 0; i < results.size(); i++) {
            EvaluationResult result = results.get(i);
            String status = result.passesAll() ? "✓" : "✗";
            
            out.printf("%n#%d %s [%.2f] %s%n",
                i + 1,
                status,
                result.overallScore(),
                truncate(result.userMessage(), 50));
            
            if (!result.passesAll()) {
                // Show failed criteria
                result.scores().entrySet().stream()
                    .filter(e -> !e.getValue().passed())
                    .forEach(e -> out.printf("     ✗ %s (%.2f): %s%n",
                        e.getKey().displayName(),
                        e.getValue().score(),
                        truncate(e.getValue().reasoning(), 60)));
            }
        }
        
        out.println();
        out.println("═══════════════════════════════════════════════════════════");
        
        return sw.toString();
    }
    
    /**
     * Generate CSV format for analysis in spreadsheets.
     */
    public static String formatCSV(List<EvaluationResult> results) {
        var sw = new StringWriter();
        var out = new PrintWriter(sw);
        
        // Header
        out.print("Turn,UserMessage,AssistantResponse,OverallScore,PassedAll");
        for (EvaluationCriteria criterion : EvaluationCriteria.values()) {
            out.printf(",%s_Score,%s_Passed", 
                criterion.name(), 
                criterion.name());
        }
        out.println();
        
        // Data rows
        for (int i = 0; i < results.size(); i++) {
            EvaluationResult result = results.get(i);
            
            out.printf("%d,\"%s\",\"%s\",%.3f,%s",
                i + 1,
                escapeCsv(result.userMessage()),
                escapeCsv(result.assistantResponse()),
                result.overallScore(),
                result.passesAll());
            
            for (EvaluationCriteria criterion : EvaluationCriteria.values()) {
                EvaluationResult.CriterionScore score = result.scores().get(criterion);
                out.printf(",%.3f,%s", score.score(), score.passed());
            }
            
            out.println();
        }
        
        return sw.toString();
    }
    
    private static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - 3) + "...";
    }
    
    private static String escapeCsv(String value) {
        return value.replace("\"", "\"\"");
    }
}
