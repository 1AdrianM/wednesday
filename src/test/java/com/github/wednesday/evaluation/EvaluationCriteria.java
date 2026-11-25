package com.github.wednesday.evaluation;

/**
 * Evaluation criteria for Wednesday responses.
 * Each criterion maps to a specific system prompt rule.
 */
public enum EvaluationCriteria {
    
    /** Respuestas cortas (1-3 frases), sin bloques largos */
    BREVITY("Brevedad", 
            "La respuesta debe tener 1-3 frases. Si el tema necesita más, dar lo esencial y ofrecer ampliar."),
    
    /** Sin markdown, listas, tablas, código */
    NO_FORMATTING("Sin Formato", 
                   "Sin asteriscos, almohadillas, guiones de lista, tablas, código ni enlaces."),
    
    /** Sin enumeraciones con viñetas o números */
    NO_LISTS("Sin Listas", 
             "Sin listas ni enumeraciones. Las opciones múltiples se unen en frases naturales."),
    
    /** Números, fechas, horas escritas como se leen */
    SPOKEN_NUMBERS("Números Hablados", 
                   "Los números, fechas, horas, monedas y símbolos se escriben como se leen en voz alta."),
    
    /** Sin emojis ni abreviaturas difíciles de pronunciar */
    NO_EMOJIS_ABBR("Sin Emojis/Abreviaturas", 
                   "Sin emojis ni abreviaturas difíciles de pronunciar."),
    
    /** Naturalidad, cercanía, sin frases de relleno */
    CONVERSATIONAL("Conversacional", 
                   "Natural y cercano, sin frases de relleno como '¡Claro que sí!' al principio."),
    
    /** No terminar con preguntas (salvo que falte información) */
    NO_TRAILING_QUESTIONS("Sin Preguntas Finales", 
                          "No terminar con preguntas ni ofrecimientos. Pregunta solo cuando falte un dato."),
    
    /** Usar español por defecto, inglés si el usuario lo usa */
    LANGUAGE_MATCHING("Idioma Correcto", 
                      "Español por defecto. Inglés si el usuario habló en inglés. Usar el idioma del último mensaje."),
    
    /** Corrección factual cuando aplique */
    FACTUAL_ACCURACY("Precisión Factual", 
                     "La información debe ser precisa y correcta (cuando sea verificable).");
    
    private final String displayName;
    private final String description;
    
    EvaluationCriteria(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
    
    public String displayName() {
        return displayName;
    }
    
    public String description() {
        return description;
    }
}
