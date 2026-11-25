package com.github.wednesday.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * LLM-as-a-Judge implementation using Gemini API.
 * Evaluates responses against Wednesday's system prompt rules.
 */
public class GeminiJudge implements ResponseJudge {
    
    private static final String API_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper json;
    
    public GeminiJudge(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key is required");
        }
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
        this.json = new ObjectMapper();
    }
    
    @Override
    public EvaluationResult evaluate(String userMessage, String assistantResponse, String previousContext) {
        String prompt = buildEvaluationPrompt(userMessage, assistantResponse, previousContext);
        
        try {
            String responseJson = callGemini(prompt);
            return parseEvaluationResponse(userMessage, assistantResponse, responseJson);
            
        } catch (Exception e) {
            throw new RuntimeException("Evaluation failed: " + e.getMessage(), e);
        }
    }
    
    private String buildEvaluationPrompt(String userMessage, String assistantResponse, String previousContext) {
        var sb = new StringBuilder();
        
        sb.append("Eres un juez experto evaluando respuestas de un asistente de voz llamado Wednesday.\n\n");
        sb.append("**REGLAS DEL SISTEMA DE WEDNESDAY:**\n");
        sb.append("- Respuestas cortas (1-3 frases)\n");
        sb.append("- Sin markdown, listas, tablas, código\n");
        sb.append("- Sin enumeraciones con viñetas o números\n");
        sb.append("- Números escritos como se leen: 'veinte', 'las diez y cuarto'\n");
        sb.append("- Sin emojis ni abreviaturas difíciles de pronunciar\n");
        sb.append("- Conversacional, natural, sin frases de relleno\n");
        sb.append("- NO terminar con preguntas (salvo que falte información)\n");
        sb.append("- Español por defecto, inglés si el usuario habló en inglés\n\n");
        
        if (previousContext != null && !previousContext.isBlank()) {
            sb.append("**CONTEXTO PREVIO:**\n").append(previousContext).append("\n\n");
        }
        
        sb.append("**TURNO A EVALUAR:**\n");
        sb.append("Usuario: ").append(userMessage).append("\n");
        sb.append("Wednesday: ").append(assistantResponse).append("\n\n");
        
        sb.append("Evalúa la respuesta de Wednesday contra cada criterio (0.0 a 1.0):\n\n");
        
        for (EvaluationCriteria criterion : EvaluationCriteria.values()) {
            sb.append(criterion.ordinal() + 1).append(". **").append(criterion.displayName()).append("**: ");
            sb.append(criterion.description()).append("\n");
        }
        
        sb.append("\nResponde en JSON con este formato exacto:\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"scores\": [\n");
        sb.append("    {\n");
        sb.append("      \"criterion\": \"BREVITY\",\n");
        sb.append("      \"score\": 0.9,\n");
        sb.append("      \"reasoning\": \"Dos frases, directo y completo\",\n");
        sb.append("      \"evidence\": \"Texto específico que demuestra el score\"\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"overallScore\": 0.85,\n");
        sb.append("  \"summary\": \"Resumen general en 1-2 líneas\"\n");
        sb.append("}\n```\n");
        
        return sb.toString();
    }
    
    private String callGemini(String prompt) throws IOException, InterruptedException {
        String requestBody = json.createObjectNode()
            .set("contents", json.createArrayNode()
                .add(json.createObjectNode()
                    .set("parts", json.createArrayNode()
                        .add(json.createObjectNode()
                            .put("text", prompt)))))
            .toString();
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(API_ENDPOINT + "?key=" + apiKey))
            .header("Content-Type", "application/json")
            .timeout(TIMEOUT)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("Gemini API error " + response.statusCode() + ": " + response.body());
        }
        
        return response.body();
    }
    
    private EvaluationResult parseEvaluationResponse(
        String userMessage,
        String assistantResponse,
        String geminiResponse
    ) throws IOException {
        
        JsonNode root = json.readTree(geminiResponse);
        String text = root.at("/candidates/0/content/parts/0/text").asText();
        
        // Extract JSON from markdown code block if present
        String jsonText = text;
        if (text.contains("```json")) {
            int start = text.indexOf("```json") + 7;
            int end = text.indexOf("```", start);
            jsonText = text.substring(start, end).trim();
        } else if (text.contains("```")) {
            int start = text.indexOf("```") + 3;
            int end = text.indexOf("```", start);
            jsonText = text.substring(start, end).trim();
        }
        
        JsonNode evaluation = json.readTree(jsonText);
        
        Map<EvaluationCriteria, EvaluationResult.CriterionScore> scores = new EnumMap<>(EvaluationCriteria.class);
        
        for (JsonNode scoreNode : evaluation.get("scores")) {
            String criterionName = scoreNode.get("criterion").asText();
            double score = scoreNode.get("score").asDouble();
            String reasoning = scoreNode.get("reasoning").asText();
            String evidence = scoreNode.has("evidence") ? scoreNode.get("evidence").asText() : "";
            
            EvaluationCriteria criterion = EvaluationCriteria.valueOf(criterionName);
            scores.put(criterion, EvaluationResult.CriterionScore.of(score, reasoning, evidence));
        }
        
        double overallScore = evaluation.get("overallScore").asDouble();
        String summary = evaluation.get("summary").asText();
        
        return new EvaluationResult(userMessage, assistantResponse, scores, overallScore, summary);
    }
}
