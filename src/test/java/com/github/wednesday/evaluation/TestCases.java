package com.github.wednesday.evaluation;

import java.util.List;

/**
 * Predefined test cases for evaluating Wednesday.
 * Each case tests specific system prompt rules.
 */
public final class TestCases {
    
    public record TestCase(
        String id,
        String description,
        String userMessage,
        String goodResponse,
        String badResponse,
        List<EvaluationCriteria> primaryCriteria
    ) {}
    
    private TestCases() {}
    
    public static final List<TestCase> ALL = List.of(
        
        // 1. Brevity test
        new TestCase(
            "TC001",
            "Debe responder brevemente (1-3 frases)",
            "¿Qué tiempo hace hoy?",
            "Hoy hace veinticinco grados y está soleado.", // GOOD: 1 frase
            "Hoy hace un tiempo muy agradable. La temperatura es de veinticinco grados centígrados. El cielo está completamente despejado y el sol brilla con fuerza. Es un día perfecto para salir a dar un paseo o hacer actividades al aire libre. No se esperan lluvias ni cambios bruscos de temperatura durante todo el día.", // BAD: demasiado largo
            List.of(EvaluationCriteria.BREVITY)
        ),
        
        // 2. No markdown
        new TestCase(
            "TC002",
            "No debe usar markdown ni formato",
            "Dame consejos para dormir mejor",
            "Mantén horarios regulares, evita pantallas antes de dormir y procura que tu habitación esté oscura y fresca.", // GOOD
            "Aquí tienes algunos consejos:\n- **Horarios regulares**: acuéstate y levántate a la misma hora\n- **Sin pantallas**: evita móvil y ordenador 1h antes de dormir\n- **Ambiente**: habitación oscura y fresca", // BAD: markdown, listas
            List.of(EvaluationCriteria.NO_FORMATTING, EvaluationCriteria.NO_LISTS)
        ),
        
        // 3. No lists
        new TestCase(
            "TC003",
            "No debe usar listas ni enumeraciones",
            "¿Qué puedo visitar en Madrid?",
            "Puedes visitar el Museo del Prado, el Parque del Retiro, la Puerta del Sol o el Palacio Real.", // GOOD: opciones en frase natural
            "Puedes visitar:\n1. Museo del Prado\n2. Parque del Retiro\n3. Puerta del Sol\n4. Palacio Real", // BAD: lista enumerada
            List.of(EvaluationCriteria.NO_LISTS, EvaluationCriteria.NO_FORMATTING)
        ),
        
        // 4. Spoken numbers
        new TestCase(
            "TC004",
            "Debe escribir números como se leen",
            "¿A qué hora empieza la película?",
            "La película empieza a las nueve y media de la noche.", // GOOD
            "La película empieza a las 21:30.", // BAD: formato numérico
            List.of(EvaluationCriteria.SPOKEN_NUMBERS)
        ),
        
        // 5. No trailing questions
        new TestCase(
            "TC005",
            "No debe terminar con preguntas (salvo que falte info)",
            "El tiempo está nublado hoy",
            "Sí, hoy está bastante nublado.", // GOOD: no pregunta
            "Sí, hoy está bastante nublado. ¿Vas a salir a algún sitio?", // BAD: pregunta innecesaria
            List.of(EvaluationCriteria.NO_TRAILING_QUESTIONS)
        ),
        
        // 6. Conversational style
        new TestCase(
            "TC006",
            "Debe ser conversacional sin frases de relleno",
            "¿Sabes quién ganó el mundial 2022?",
            "Argentina ganó el Mundial de dos mil veintidós.", // GOOD: directo
            "¡Claro que sí! ¡Por supuesto! Argentina ganó el Mundial de dos mil veintidós. ¡Fue una final increíble!", // BAD: rellenos excesivos
            List.of(EvaluationCriteria.CONVERSATIONAL)
        ),
        
        // 7. Language matching - Spanish
        new TestCase(
            "TC007",
            "Debe responder en español por defecto",
            "¿Cómo estás?",
            "Estoy bien, gracias. ¿Y tú?", // GOOD: español
            "I'm fine, thanks. How are you?", // BAD: inglés cuando debería ser español
            List.of(EvaluationCriteria.LANGUAGE_MATCHING)
        ),
        
        // 8. Language matching - English
        new TestCase(
            "TC008",
            "Debe responder en inglés si el usuario habla inglés",
            "What time is it?",
            "It's ten forty-five in the morning.", // GOOD: inglés
            "Son las diez y cuarenta y cinco de la mañana.", // BAD: español cuando debería ser inglés
            List.of(EvaluationCriteria.LANGUAGE_MATCHING, EvaluationCriteria.SPOKEN_NUMBERS)
        ),
        
        // 9. No emojis
        new TestCase(
            "TC009",
            "No debe usar emojis",
            "Me voy de vacaciones mañana",
            "Qué bien, que lo disfrutes mucho.", // GOOD: sin emojis
            "¡Qué bien! 🎉🌴 Que lo disfrutes mucho. ✈️", // BAD: con emojis
            List.of(EvaluationCriteria.NO_EMOJIS_ABBR)
        ),
        
        // 10. Ask when info missing
        new TestCase(
            "TC010",
            "Debe preguntar solo cuando falte información necesaria",
            "Ponme una alarma",
            "¿A qué hora quieres la alarma?", // GOOD: pregunta porque falta info
            "Claro, te pongo una alarma para mañana a las siete.", // BAD: asume sin preguntar
            List.of(EvaluationCriteria.NO_TRAILING_QUESTIONS)
        ),
        
        // 11. Currency spoken
        new TestCase(
            "TC011",
            "Debe escribir cantidades como se leen",
            "¿Cuánto cuesta eso?",
            "Cuesta cuarenta y cinco euros con noventa y nueve.", // GOOD
            "Cuesta 45,99€.", // BAD: formato numérico
            List.of(EvaluationCriteria.SPOKEN_NUMBERS)
        ),
        
        // 12. Percentage spoken
        new TestCase(
            "TC012",
            "Debe escribir porcentajes como se leen",
            "¿Cuánta batería tengo?",
            "Tienes setenta y cinco por ciento de batería.", // GOOD
            "Tienes 75% de batería.", // BAD: símbolo %
            List.of(EvaluationCriteria.SPOKEN_NUMBERS)
        )
    );
    
    /**
     * Get test cases by ID.
     */
    public static TestCase getById(String id) {
        return ALL.stream()
            .filter(tc -> tc.id().equals(id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Test case not found: " + id));
    }
    
    /**
     * Get test cases that test a specific criterion.
     */
    public static List<TestCase> getByCriterion(EvaluationCriteria criterion) {
        return ALL.stream()
            .filter(tc -> tc.primaryCriteria().contains(criterion))
            .toList();
    }
}
