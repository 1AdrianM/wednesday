# Quick Start: Wednesday Evaluation System

## Setup (1 minuto)

```bash
# 1. Configurar API key
export GEMINI_API_KEY='tu_api_key_aqui'

# 2. Ejecutar evaluación de ejemplo
cd /path/to/wednesday
./eval single
```

## Ejemplo de Salida

```
╔══════════════════════════════════════════════════════════╗
║  SINGLE TURN EVALUATION                                  ║
╚══════════════════════════════════════════════════════════╝

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

Números Hablados [✓ PASS] 1.00/1.0
  La hora está escrita completamente en palabras

Conversacional [✓ PASS] 0.90/1.0
  Tono natural y cercano sin rellenos

... (9 criterios total)

───────────────────────────────────────────────────────────
RESUMEN
───────────────────────────────────────────────────────────
Score General: 0.92/1.0
Criterios Pasados: 9/9
Estado: ✓ APROBADO

Respuesta excelente que cumple todas las reglas del sistema.
═══════════════════════════════════════════════════════════
```

## Uso Común

### Durante Desarrollo

```bash
# Después de cambiar el system prompt
./eval good    # ¿Siguen pasando las respuestas correctas?

# Verificar detección de violaciones
./eval bad     # ¿Se detectan las respuestas incorrectas?
```

### Pre-Release

```bash
# Suite completa (24 evaluaciones)
./eval all

# Revisar reportes
ls -lt target/evaluation-reports/
cat target/evaluation-reports/evaluation-report-*.txt
```

### Integración en Código

```java
import com.github.wednesday.evaluation.*;

// Setup
ResponseJudge judge = new GeminiJudge(System.getenv("GEMINI_API_KEY"));

// Evaluar respuesta en vivo
String userMsg = "¿Qué tiempo hace?";
String assistantResp = aiEngine.respond(userMsg);

EvaluationResult result = judge.evaluate(userMsg, assistantResp);

// Verificar calidad
if (!result.passesAll()) {
    System.out.println("⚠️ Quality issues detected:");
    result.scores().entrySet().stream()
        .filter(e -> !e.getValue().passed())
        .forEach(e -> System.out.printf("  - %s: %s%n",
            e.getKey().displayName(),
            e.getValue().reasoning()));
}
```

## Interpretación de Scores

| Score | Significado |
|-------|-------------|
| 0.90 - 1.00 | ✅ Excelente |
| 0.70 - 0.89 | ✓ Aceptable (pasa threshold) |
| 0.50 - 0.69 | ⚠️ Mejorable (no pasa) |
| 0.00 - 0.49 | ❌ Malo (violación clara) |

## Troubleshooting

### "API key not set"
```bash
export GEMINI_API_KEY='tu_key'
```

### "Rate limit exceeded"
Espera 60 segundos o ajusta el delay en `WednesdayEvaluationSuite.java`:
```java
Thread.sleep(2000); // Aumentar a 2s
```

### Scores muy bajos inesperados
- Revisar el system prompt en `src/main/resources/prompts/system.txt`
- Verificar que las respuestas realmente cumplen las reglas
- El juez es estricto: 0.70 es el threshold

### Evaluación demasiado lenta
```bash
# Usar single turn para tests rápidos
./eval single    # ~3s

# En lugar de full suite
./eval all       # ~60s
```

## Siguientes Pasos

1. **Leer documentación completa**: `docs/EVALUATION.md`
2. **Ver casos de prueba**: `TestCases.java` (12 escenarios)
3. **Explorar reportes CSV**: Análisis en Excel/Sheets
4. **Integrar en workflow**: CI/CD, pre-release checks

## Support

- Documentación: `docs/EVALUATION.md`
- ADR: `docs/adr/0004-llm-as-judge-evaluation.md`
- Issues: Contactar al owner del proyecto
