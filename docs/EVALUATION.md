# Wednesday LLM-as-a-Judge Evaluation System

Sistema de evaluación automatizada para Wednesday usando Gemini como juez experto.

## 🎯 Propósito

Evalúa las respuestas de Wednesday contra las reglas del system prompt de forma automatizada y consistente, usando **LLM-as-a-Judge** pattern.

## 📋 Criterios de Evaluación

| Criterio | Descripción |
|----------|-------------|
| **Brevedad** | Respuestas de 1-3 frases |
| **Sin Formato** | Sin markdown, listas, tablas, código |
| **Sin Listas** | Opciones múltiples en frases naturales |
| **Números Hablados** | "veinte grados", "las diez y cuarto" |
| **Sin Emojis/Abreviaturas** | Solo texto pronunciable |
| **Conversacional** | Natural, cercano, sin rellenos |
| **Sin Preguntas Finales** | Solo preguntar cuando falte info |
| **Idioma Correcto** | Español por defecto, inglés si el usuario lo usa |
| **Precisión Factual** | Información correcta (cuando sea verificable) |

## 🚀 Uso

### Requisitos

```bash
export GEMINI_API_KEY="tu_api_key"
# Opcional: carpeta de salida para reportes
export WEDNESDAY_EVAL_OUTPUT_DIR="./eval-reports"
```

### Ejecutar Suite Completa

```bash
# Evaluar respuestas buenas (12 casos)
mvn test -Dtest=WednesdayEvaluationSuite#evaluateGoodResponses

# Evaluar respuestas malas (detectar violaciones)
mvn test -Dtest=WednesdayEvaluationSuite#evaluateBadResponses

# Evaluar un turno individual
mvn test -Dtest=WednesdayEvaluationSuite#evaluateSingleTurn

# Ejecutar todos
mvn test -Dtest=WednesdayEvaluationSuite
```

### Uso Programático

```java
// 1. Crear el juez
ResponseJudge judge = new GeminiJudge(System.getenv("GEMINI_API_KEY"));

// 2. Evaluar una respuesta
EvaluationResult result = judge.evaluate(
    "¿Qué hora es?",                        // user message
    "Son las tres y veinte de la tarde."    // assistant response
);

// 3. Revisar resultados
System.out.printf("Score: %.2f%n", result.overallScore());
System.out.printf("Passed: %d/%d%n", result.passedCount(), result.totalCriteria());

result.scores().forEach((criterion, score) -> {
    if (!score.passed()) {
        System.out.printf("%s FAILED: %s%n", 
            criterion.displayName(), 
            score.reasoning());
    }
});

// 4. Generar reporte
String report = EvaluationReport.formatSingle(result);
System.out.println(report);
```

### Evaluar Múltiples Turnos

```java
List<EvaluationResult> results = new ArrayList<>();

for (Turn turn : conversationTurns) {
    EvaluationResult result = judge.evaluate(
        turn.userMessage(),
        turn.assistantResponse()
    );
    results.add(result);
}

// Reporte agregado
String suiteReport = EvaluationReport.formatSuite(results);
String csvReport = EvaluationReport.formatCSV(results);

// Guardar
Files.writeString(Path.of("report.txt"), suiteReport);
Files.writeString(Path.of("report.csv"), csvReport);
```

## 📊 Formato de Reportes

### Console Output

```
═══════════════════════════════════════════════════════════
EVALUACIÓN DE RESPUESTA
═══════════════════════════════════════════════════════════

Usuario: ¿Qué hora es?
Wednesday: Son las tres y veinte de la tarde.

───────────────────────────────────────────────────────────
SCORES POR CRITERIO
───────────────────────────────────────────────────────────

Brevedad [✓ PASS] 0.95/1.0
  Respuesta muy concisa, una sola frase directa
  Evidencia: "Son las tres y veinte de la tarde."

Números Hablados [✓ PASS] 1.00/1.0
  La hora está escrita completamente en palabras
  Evidencia: "tres y veinte"

...

───────────────────────────────────────────────────────────
RESUMEN
───────────────────────────────────────────────────────────
Score General: 0.92/1.0
Criterios Pasados: 9/9
Estado: ✓ APROBADO

Respuesta excelente que cumple todas las reglas del sistema.
═══════════════════════════════════════════════════════════
```

### CSV Export

Exporta a CSV para análisis en Excel/Sheets:
- Score por criterio
- Pass/Fail por criterio
- Metadata del turno
- Timestamps

## 🧪 Casos de Prueba

12 casos predefinidos en `TestCases.java`:

| ID | Descripción | Criterios Principales |
|----|-------------|----------------------|
| TC001 | Brevedad | BREVITY |
| TC002 | No markdown | NO_FORMATTING, NO_LISTS |
| TC003 | No listas | NO_LISTS |
| TC004 | Números hablados | SPOKEN_NUMBERS |
| TC005 | Sin preguntas finales | NO_TRAILING_QUESTIONS |
| TC006 | Estilo conversacional | CONVERSATIONAL |
| TC007 | Español por defecto | LANGUAGE_MATCHING |
| TC008 | Inglés cuando corresponde | LANGUAGE_MATCHING |
| TC009 | Sin emojis | NO_EMOJIS_ABBR |
| TC010 | Preguntar cuando falta info | NO_TRAILING_QUESTIONS |
| TC011 | Monedas habladas | SPOKEN_NUMBERS |
| TC012 | Porcentajes hablados | SPOKEN_NUMBERS |

Cada caso tiene:
- **Good response**: ejemplo que cumple las reglas
- **Bad response**: ejemplo que viola las reglas

## 🎨 Arquitectura

```
evaluation/
├── EvaluationCriteria.java      # Enum con los 9 criterios
├── EvaluationResult.java        # Modelo de resultado (scores + summary)
├── ResponseJudge.java           # Interfaz del juez
├── GeminiJudge.java            # Implementación con Gemini API
├── TestCases.java              # 12 casos de prueba predefinidos
├── EvaluationReport.java       # Generador de reportes (text, CSV)
└── WednesdayEvaluationSuite.java # JUnit tests ejecutables
```

## 📈 Scoring

**Por Criterio:**
- 0.0 - 0.69: ✗ FAIL (no cumple)
- 0.70 - 1.0: ✓ PASS (cumple)

**Overall Score:**
- Promedio de todos los criterios
- Umbral recomendado: ≥ 0.70

**Suite Completo:**
- % de respuestas que pasan todos los criterios
- Promedio por criterio
- Detección de patrones de fallas

## 🔧 Configuración

### Variables de Entorno

```bash
# Requerida
GEMINI_API_KEY=your_api_key

# Opcional
WEDNESDAY_EVAL_OUTPUT_DIR=./eval-reports  # default: target/evaluation-reports
```

### Dependencias Maven

Ya incluidas en el proyecto:
- JUnit 5 (testing)
- Jackson (JSON parsing)
- Java HttpClient (Gemini API)

## 📝 Extender el Sistema

### Agregar Nuevo Criterio

1. **Agregar enum en `EvaluationCriteria.java`:**
```java
NEW_CRITERION("Nombre", "Descripción clara del criterio")
```

2. **Actualizar prompt en `GeminiJudge.java`:**
El prompt se genera automáticamente desde el enum.

3. **Agregar casos de prueba en `TestCases.java`:**
```java
new TestCase(
    "TC013",
    "Descripción del test",
    "Mensaje del usuario",
    "Respuesta buena",
    "Respuesta mala",
    List.of(NEW_CRITERION)
)
```

### Usar Otro LLM

Implementar `ResponseJudge`:

```java
public class ClaudeJudge implements ResponseJudge {
    @Override
    public EvaluationResult evaluate(
        String userMessage,
        String assistantResponse,
        String previousContext
    ) {
        // Call Claude API
        // Parse response
        // Return EvaluationResult
    }
}
```

## 🎯 Uso Recomendado

### Durante Desarrollo

```bash
# Después de cambiar el system prompt
mvn test -Dtest=WednesdayEvaluationSuite#evaluateGoodResponses

# Verificar que detecta violaciones
mvn test -Dtest=WednesdayEvaluationSuite#evaluateBadResponses
```

### Pre-Release

```bash
# Evaluar conversación completa real
# Grabar 10-20 turnos reales
# Ejecutar evaluación batch
# Revisar CSV para patrones
```

### Monitoreo Continuo

```bash
# CI/CD: evaluar sample de respuestas reales
# Alert si average score < 0.70
# Track score trends over time
```

## ⚠️ Limitaciones

1. **Rate Limits**: Gemini tiene límites de requests/min
   - Se agrega delay de 1s entre evaluaciones
   - Ajustar según tu tier API

2. **Costo**: Cada evaluación es 1 request a Gemini
   - 12 casos = 12 requests
   - Monitorear uso de API

3. **Variabilidad**: LLM-as-a-Judge puede variar ligeramente
   - Scores pueden variar ±0.05 entre ejecuciones
   - Promediar múltiples ejecuciones para mayor confianza

4. **Context Window**: Turnos individuales por ahora
   - Language matching necesita contexto de múltiples turnos
   - Implementar con `previousContext` parameter

## 📚 Referencias

- **LLM-as-a-Judge Pattern**: [Paper](https://arxiv.org/abs/2306.05685)
- **Wednesday System Prompt**: `src/main/resources/prompts/system.txt`
- **Gemini API Docs**: https://ai.google.dev/

## 🎓 Ejemplos de Uso

Ver `WednesdayEvaluationSuite.java` para ejemplos completos de:
- Evaluación batch
- Single turn evaluation
- Reporte generation
- Assertions en tests

---

**Nota**: Este sistema de evaluación es una herramienta de desarrollo. No reemplaza testing humano, pero escala la evaluación de adherencia al system prompt de forma consistente y automatizada.
