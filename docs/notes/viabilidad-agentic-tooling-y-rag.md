# Viabilidad: agentic tooling y advanced RAG en Wednesday

Análisis del 2026-11-22, a petición del dueño. No es una decisión: las decisiones se toman en las Fases 5 (Tools) y 6 (Memory), cada una con su ADR. Aquí se responde si es viable, dónde encaja y qué cuesta.

**Resumen:** las dos cosas son viables y **ya caben en el plan**:
- *agentic tooling* = Fase 5;
- RAG = Fase 6, cuyo P6-R4 ya es "agentic RAG": recuperar sólo cuando el modelo lo decide.

Lo que no es viable en v1 es la versión "máxima" de ambas: agentes autónomos de larga duración y un RAG con reranking, GraphRAG y similares. Ahí el coste en latencia y en cuota supera el beneficio para un asistente de voz personal con un corpus pequeño.

---

## 1. La restricción que lo decide todo: estamos en voz

En un chat de texto, un agente puede tardar 10 s en pensar y nadie se queja. En voz, cada silencio pesa. Hoy, sin herramientas, el primer audio ya llega en ~2 s (objetivo: 1,2 s). Ver `latency-breakdown-2026-11-22.md`.

Lo que añade cada ronda de herramienta (estimación, falta medirla):

| Paso | Coste aproximado |
|---|---|
| El LLM decide llamar a la herramienta (la `functionCall` llega completa, no token a token) | 0,5–1 s |
| La herramienta se ejecuta | desde 0 (local) hasta segundos (red) |
| Segunda llamada al LLM con el resultado, hasta el primer token | 0,5–0,8 s |
| **Total por ronda** | **+1–2 s sobre el turno** |

Consecuencias de diseño, que la spec ya recoge:
- **Frase de relleno** si la herramienta tarda (P5-R5): "Un momento, lo miro…".
- **Máximo de llamadas por turno** (P5-R4). Cada ronda suma latencia y una petición más a la cuota.
- **Confirmación hablada** para acciones con efectos (P5-R6). En voz no hay botón de "deshacer".

**Cuota:** cada ronda de herramienta es una petición extra al LLM. Con el plan gratuito, la cuota del TTS (10 al día) ya es el límite. Las herramientas no lo empeoran, porque el TTS sólo se llama al hablar la respuesta final, pero sí gastan la cuota del LLM.

---

## 2. Agentic tooling: niveles

| Nivel | Qué es | ¿Viable en Wednesday? | Dónde |
|---|---|---|---|
| 1. Una herramienta por turno | "¿Qué notas tengo?" → `read_notes` → respuesta | **Sí** | Fase 5 |
| 2. Bucle de herramientas con tope | Dos o tres llamadas encadenadas: buscar y luego guardar | **Sí, con relleno y tope bajo (2–3)** | Fase 5 (P5-R4) |
| 3. Cliente MCP | Herramientas externas (calendario, correo) servidas por servidores MCP | **Viable más adelante**, detrás de nuestra interfaz `Tool`. Una herramienta cada vez | Después de v1 (IDEAS ya lista "calendar and email tools") |
| 4. Agente autónomo de larga duración | "Investiga esto y avísame", con un plan de varios pasos en segundo plano | **No en v1.** Rompe el modelo de turno de voz, y necesita notificaciones y control de costes | IDEAS |

### Qué ya está preparado en el código
- `ModelEvent.ToolCall(id, name, argumentsJson)` existe desde la Fase 0, y `FakeLanguageModel` ya sabe emitir llamadas a herramientas en los tests.
- `AiEngine` ya es un bucle con cola de señales. El bucle de herramientas (P5-R4) es otra señal más en esa misma cola.
- `VoiceSession` es un actor. La confirmación hablada (P5-R6) es **un estado más** en la máquina de estados (p. ej. `CONFIRMING`), con su fila en la tabla y su test.

### Hallazgo práctico: Wednesday hoy se inventa la hora
En las pruebas reales respondió "Son las diez y cuarto de la mañana" a las 21:48. El modelo no sabe la hora. Se puede arreglar de dos maneras:
- **Como herramienta** (`current_time`, P5-R7): cuesta una ronda, es decir, +1–2 s.
- **Inyectando la fecha y la hora en el contexto** de cada petición: cuesta 0 ms y unos pocos tokens.

Recomendación: inyectarla en el contexto y dejar la herramienta para cálculos ("¿qué día cae el próximo martes?"). La pregunta de diseño general es: **¿esto lo necesita el modelo siempre o sólo a veces?** Si lo necesita siempre y es pequeño, va al contexto. Si es a veces, o caro, va como herramienta. Esa decisión toca la Fase 5 y se tomará allí.

### Riesgo técnico que conviene comprobar antes (spike)
Hay que ver cómo llega una `functionCall` por `streamGenerateContent` (SSE) con `gemini-3.5-flash-lite`: en qué fragmento, si mezcla texto y llamada, y cómo se devuelve el `functionResponse`. La documentación muestra sobre todo ejemplos sin streaming. En la Fase 3 aprendimos que la doc y la API real difieren (`generationComplete` frente a `turnComplete`), así que **primero se prueba contra la API real y luego se copia lo observado al fake**.

---

## 3. Advanced RAG: qué técnicas valen para Wednesday

Lo primero es el **tamaño del corpus**. La memoria de la Fase 6 son hechos y preferencias del dueño, con una política estricta de escritura (P6-R2). Hablamos de **decenas o cientos de entradas**, no de millones de documentos. Muchas técnicas de "advanced RAG" existen para corpus grandes y ruidosos.

| Técnica | Qué hace | Valor aquí | Coste en voz | Veredicto |
|---|---|---|---|---|
| Recuperar sólo cuando hace falta (agentic RAG) | La recuperación es una herramienta y el modelo decide si la usa | Alto: un saludo no paga búsqueda | 1 ronda cuando se usa | **Sí, ya es P6-R4** |
| Consulta autocontenida | El modelo llama a la herramienta con una consulta ya reescrita ("madre del usuario, cumpleaños") en vez de "lo que te dije de ella" | Alto | 0 extra: va dentro de la misma llamada | **Sí**, describiéndolo bien en la herramienta |
| Búsqueda híbrida (BM25 + vectores) | Palabras exactas (nombres, fechas) más significado | Alto: los nombres propios fallan sólo con vectores | Pequeño si es local | **Sí** |
| Fusión de rankings (RRF u otra) | Combina las dos listas de resultados | Alto | ~0 | **Sí, y es P6-R8 `// HUMAN`**: la escribes tú. Es la pieza más didáctica |
| Reranking con LLM o cross-encoder | Reordena los candidatos con un modelo | Bajo con pocos candidatos | +0,5–1 s o una dependencia pesada | **No en v1** |
| HyDE (respuesta hipotética como consulta) | Genera una respuesta falsa y busca con ella | Bajo | +1 llamada al LLM | **No** |
| GraphRAG / grafo de conocimiento | Entidades y relaciones | Interesante con miles de hechos | Alto (extracción, mantenimiento) | **No en v1**, IDEAS |
| RAG sobre documentos (notas largas, PDFs) | Trocear y buscar en tus documentos | Depende de si lo usarías | Escribir es lento pero fuera de línea; buscar, igual que arriba | **Más adelante**, si aparece el caso de uso |

### Embeddings: tres caminos

| Opción | Pros | Contras |
|---|---|---|
| **API de Gemini** (`gemini-embedding-001`, o `gemini-embedding-2` multimodal) | Sin dependencias nuevas, buena calidad, misma key | +100–300 ms por consulta (estimado); usuarios reportan 429 en el plan gratuito; datos personales salen a la API |
| **Modelo local** (ONNX vía una librería Java) | Sin red ni cuota; los datos no salen de casa | Dependencia pesada; el proyecto prefiere evitar frameworks grandes |
| **Sólo BM25 al principio** (Lucene) | Una librería, local y rápido; Lucene trae además búsqueda vectorial (HNSW) para después | Sin búsqueda semántica hasta añadir embeddings |

**Patrón importante:** las escrituras de memoria son raras y ocurren **al final de la sesión** (P6-R3), fuera del camino de latencia. El embedding de cada memoria se calcula al guardarla y se cachea. Sólo la consulta paga un embedding, y sólo cuando el modelo decide buscar.

---

## 4. Propuesta (para decidir en su fase, no ahora)

1. **Fase 5 tal cual está en la spec**, con dos añadidos que vale la pena discutir allí:
   - fecha y hora en el contexto en vez de herramienta;
   - un spike de una hora para ver el formato real de `functionCall` en streaming.
2. **Fase 6:**
   - `Memory` sobre Lucene (BM25 primero, vectores después) o un almacén aún más simple;
   - recuperación como herramienta;
   - fusión híbrida escrita por ti (P6-R8);
   - embeddings con Gemini cacheados al escribir, si la cuota aguanta.
3. **A IDEAS:** agente autónomo de larga duración, GraphRAG, reranking, RAG sobre documentos, cliente MCP.

## 5. Decisiones que son tuyas
- **Cuota y proveedor.** Con 10 voces al día no se puede probar con calma ni las herramientas ni la memoria. Es la misma decisión aparcada en PROGRESS.
- **Privacidad.** ¿Aceptas que los textos de tu memoria salgan a la API de embeddings de Google, o prefieres embeddings locales aunque cueste una dependencia?
- **Alcance de RAG.** ¿Sólo memoria personal (spec) o también tus documentos y notas? Lo segundo es una ampliación y entraría como idea nueva.

---

## 6. Preferencia del dueño: el uso de herramientas debe sonar natural (2026-11-22)

> "Lo más natural en el caso de tool use es hacerlo lo más natural posible, tener fallbacks, como: «Estoy haciendo tal búsqueda web como me pidió, jefe… ya casi termino… resultados de la búsqueda»."

Esto va **más allá de P5-R5**, que pide "una frase de relleno, una vez". Propuesta para cuando empiece la Fase 5: ampliar P5-R5 con el visto bueno del dueño.

### Escalera de narración según lo que tarda la herramienta
| Tiempo de la herramienta | Qué dice Wednesday |
|---|---|
| < ~0,7 s | Nada: la respuesta llega sola |
| > ~0,7 s | Anuncia qué hace y con qué: "Estoy buscando «vuelos a Lisboa» en la web, jefe." |
| > ~4 s | Una actualización, sin repetirse: "Ya casi termino…" o "Está tardando un poco más de lo normal." |
| Resultado | Lo resume al estilo hablado: "Encontré tres opciones; la más barata es…". Nunca lee la lista en bruto |
| Fallo o timeout | Lo cuenta y ofrece una salida: "No he podido entrar a la web ahora mismo. ¿Lo intento otra vez o te digo lo que sé sin buscar?" |

Los umbrales son ideas iniciales y se medirán; la referencia humana está en `turn-taking-timing.md`.

### Decisiones técnicas que esto implica
- **Quién redacta el anuncio.**
  - **Plantilla por herramienta:** cada `Tool` declara su frase de progreso con huecos para los argumentos ("Estoy buscando «{query}» en la web"). Es instantánea, predecible y no gasta una llamada extra al LLM.
  - **Preámbulo del propio modelo:** el LLM escribe una frase antes de la `functionCall`. Suena más variado, pero depende de que Gemini mezcle texto y llamada en el mismo stream (está en el spike pendiente).
  - **Propuesta:** usar el preámbulo si llega y la plantilla si no.
- **El TTS es el problema escondido.**
  - **Latencia:** sintetizar el relleno cuesta ~1,2 s, casi tanto como lo que quiere tapar.
  - **Cuota:** con 10 peticiones de voz al día, cada relleno gasta una.
  - **Solución:** **cachear el audio de las frases fijas** ("ya casi termino", "un momento") al arrancar o la primera vez, y sintetizar sólo la parte variable. Encaja con lo aparcado sobre la cuota del TTS.
- **Fallbacks en capas:**
  1. reintentar una vez si el error es transitorio;
  2. si no, devolver al modelo un error estructurado (P5-R2/R3) para que lo explique con naturalidad;
  3. si aplica, responder con lo que sabe, avisando de que no pudo comprobarlo.
- **Interrumpir:** si el usuario habla durante la narración, se cancela la herramienta cuando se pueda (P5-R5 y Fase 4).
- **Tratamiento ("jefe").** Es una decisión de personalidad del prompt de sistema y está pendiente de confirmar con el dueño.

## Fuentes
- Function calling con la API de Gemini: https://ai.google.dev/gemini-api/docs/generate-content/function-calling
- Function calling en Vertex AI (flujo `functionCall` / `functionResponse`): https://docs.cloud.google.com/vertex-ai/generative-ai/docs/multimodal/function-calling
- Gemini Embedding (GA, dimensiones Matryoshka): https://developers.googleblog.com/en/gemini-embedding-available-gemini-api/
- Gemini Embedding 2: https://ai.google.dev/gemini-api/docs/models/gemini-embedding-2-preview?hl=en
