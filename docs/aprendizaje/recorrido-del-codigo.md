# Recorrido por el código de Wednesday

Notas de las sesiones de recorrido guiado: qué hace cada pieza, qué patrones usa y qué decisiones de diseño de sistemas hay detrás. Se escribe a medida que avanzamos; al final de cada sesión queda la pregunta pendiente para retomar.

- Recurso interactivo de la lección 2 (simulador de la carrera entre hilos): https://claude.ai/artifact/NW8saFpaQ7uUx86dhQq36K
- Estado del proyecto: Fases 0, 1 y 2 terminadas; Fase 3 construida (voz de entrada, Gateway de voz, máquina de estados) y pendiente de tu prueba de aceptación.
- Cada fase añade aquí sus lecciones al terminar (ver "Lecciones de la Fase 3").

---

## Mapa general

```
                 CLIENTES (cómo entras)
   ┌──────────────────────┐        ┌──────────────────────────┐
   │ cli/ChatCli (+voz)   │        │ index.html (navegador)   │
   └──────────┬───────────┘        └────────────┬─────────────┘
              │                                 │ WebSocket /ws/voice
              │                                 │ (binario: audio · texto: JSON)
              │                     ┌───────────▼─────────────┐
              │                     │ gateway/VoiceSocket     │  traduce y valida tramas
              │                     │ gateway/InputFrames     │  (ArchUnit: sin lógica)
              │                     └───────────┬─────────────┘
              ▼                                 ▼
   ┌─────────────────────────────────────────────────────────┐
   │ session/  VoiceSession (actor) · SessionStateMachine    │  orquesta
   │           SpokenReply · VoiceSessions                   │
   └──┬──────────────┬──────────────────┬──────────────┬─────┘
      ▼              ▼                  ▼              ▼
   ┌────────────┐ ┌──────────────┐ ┌──────────────┐ ┌────────────────────┐
   │ stt/       │ │ ai/AiEngine  │▶│ context/     │ │ tts/SentenceChunker│
   │ SpeechTo-  │ │ (reintentos, │ │ Conversation │ │ tts/TextToSpeech   │
   │ Text       │ │  timeouts)   │ │ Context      │ └─────────┬──────────┘
   └─────┬──────┘ └──────┬───────┘ └──────────────┘           │
         ▼               ▼ interfaz LanguageModel             ▼ interfaz TextToSpeech
   ┌──────────────┐ ┌──────────────────────┐        ┌──────────────────────┐
   │ stt.provider │ │ ai.provider.gemini   │← sólo  │ tts.provider.gemini  │
   │ .gemini      │ │ ai.fake              │  aquí  │ tts.fake             │
   │ stt.fake     │ └──────────────────────┘ se habla con Google ──────────┘
   └──────────────┘

   common/  ← lo compartido: StreamListener/StreamHandle, VirtualThreadStream,
              AudioChunk, PcmFormat, AudioFormats, SessionId, Timeline   (no depende de nadie)
```

---

## Lección 1. La frontera con los proveedores

**Archivos:** `ai/LanguageModel.java`, `ai/LanguageModels.java`, `ai/provider/gemini/`, `ai/fake/`, `ArchitectureTest.java`.

- **Estrategia:** `LanguageModel` es una interfaz con varias implementaciones intercambiables (`GeminiLanguageModel`, `FakeLanguageModel`). El resto del sistema sólo conoce la interfaz.
- **Fábrica simple:** `LanguageModels.create("gemini" | "fake", settings)` elige la implementación por nombre. Es el "interruptor". Lo mismo para la voz con `TextToSpeechs`.
- **Cambiar de proveedor** (por ejemplo a Claude) significa crear `ai/provider/claude/ClaudeLanguageModel.java` y añadir un `case` en la fábrica. Nada más cambia: ni `AiEngine`, ni la sesión, ni el CLI, ni el navegador.
- **ArchUnit lo hace cumplir:** si una clase fuera del componente `ai` usa algo de `ai.provider..`, el build falla. La regla no depende de la disciplina de nadie.
- **Trade-off elegido:** el `switch` es cerrado (añadir un proveedor obliga a editar la fábrica). La alternativa sería descubrir proveedores solos (CDI, `ServiceLoader`). Con 2 o 3 proveedores, lo explícito es más fácil de seguir que la "magia".

---

## Lección 2. El contrato de streaming y el candado

**Archivos:** `common/StreamListener.java`, `common/StreamHandle.java`, `common/VirtualThreadStream.java`.

### Las tres piezas

```
  PRODUCTOR (Gemini, fakes)            CONSUMIDOR (AiEngine, sesión, CLI)
  escribe código secuencial            recibe callbacks
          │                                      ▲
          ▼                                      │
     ┌─────────┐      VirtualThreadStream    ┌──────────────┐
     │ Emitter │ ──── (candado, finished, ──▶│StreamListener│
     └─────────┘       hilo virtual, MDC)    └──────────────┘
                                                     │
                       StreamHandle.cancel() ◀───────┘
```

- **`StreamListener`** (lo implementa el consumidor): `onEvent`, `onComplete`, `onError`.
- **`StreamHandle`** (lo recibe el consumidor): `cancel()`, `isActive()`, `completion()`.
- **`Emitter`** (lo recibe el productor): `emit(e)`, `isCancelled()`, `sleep(d)`.
- **`VirtualThreadStream`** está en medio y es el único que conoce el candado. El productor nunca ve el listener; el consumidor nunca ve al productor.

### Por qué el Emitter tiene esos métodos

| Método | Para qué |
|---|---|
| `emit(e)` | Entrega pasando por `deliver()`, con candado. El productor no puede saltarse la regla "nada después de cancelar". |
| `isCancelled()` | Cancelación cooperativa: el productor deja de *trabajar*, no sólo de entregar. Ahorra red y cuota de Gemini. |
| `sleep(d)` | Espera que vuelve en cuanto se cancela. La usan los fakes para simular latencia. |

Lo que el productor **no** tiene: `onComplete` y `onError`. Los decide el stream según cómo termina el cuerpo del productor. Esto es **inversión de control**.

### Ciclo de vida de `runBody`

```java
try {
    body.run(emitter);            // el productor trabaja y llama a emit(...)
    finish(null);                 // retornó normal          → onComplete()
} catch (InterruptedException e) {
    finish(finished ? null : e);  // lo interrumpió cancel() → silencio
} catch (Exception | Error e) {
    finish(e);                    // lanzó una excepción     → onError(e)
}
```

Regla del contrato: *un stream no cancelado termina con exactamente un `onComplete` o un `onError`; después de `cancel()`, silencio.*

### El candado: modelo mental de dos operaciones

| Operación | Qué hace |
|---|---|
| `lock()` | Si está libre, lo coges y sigues. Si está ocupado, **esperas** aquí hasta que quede libre. |
| `unlock()` | Lo sueltas. Si alguien esperaba en `lock()`, ese despierta y lo coge. |

Nadie "pasa" el candado: uno suelta y el que esperaba entra (como la llave de un baño de bar). Quien **tiene** el candado sigue trabajando; quien lo **pide** mientras está ocupado es quien espera.

### La carrera que evita: comprobar y luego actuar

Sin candado, el productor comprueba `if (!finished)` y, antes de entregar, la sesión cancela y se pone a escuchar. Resultado: suena un trozo de audio viejo encima de la voz del usuario.

```
hilo productor (audio)               hilo sesión (barge-in)
if (!finished)   → true
                                     finished = true
                                     cancel() retorna → escuchando
callback.run()   → ¡audio viejo!
```

Con candado, el orden queda así:

```
productor                         sesión (barge-in)
1. lock()          ← tiene el candado
2. if (!finished)  → true
                                  3. cancel(): lock()  → ocupado, espera
4. callback.run()  → entrega
5. unlock()
                                  6. consigue el candado
                                  7. finished = true
                                  8. unlock()
                                  9. interrupt(); cancel() retorna → escucha
10. lock(); if (!finished) → false → no entrega y sale
```

### Lo que hay que recordar

1. **El candado convierte "comprobar y entregar" en un solo paso** frente a cualquiera que también use el candado.
2. **`cancel()` espera ese candado antes de retornar**, así que cuando vuelve hay silencio garantizado. Esto es lo que protege el barge-in.
3. **`cancel()` y el final natural (`finish()`) compiten por el mismo candado y comprueban `finished` dentro de él**, así que exactamente uno gana: nunca hay un `onComplete` de un stream cancelado ni dos finales. Por eso `finished` se escribe *dentro* del candado.
4. **`unlock()` va siempre en un `finally`**: si un callback lanza una excepción, el candado se suelta igual. Sin `finally` habría un interbloqueo (deadlock).
5. **`ReentrantLock`**: el hilo que ya tiene el candado puede pedirlo otra vez. Importa si un listener llama a `cancel()` desde dentro de su propio `onEvent`.
6. **Los listeners deben retornar rápido.** Si un callback tarda 2 s, un `cancel()` desde otro hilo también tarda 2 s, porque espera el candado. Por eso el `SpeakerSink` del CLI tiene su propia cola y su propio hilo en lugar de escribir directo en la tarjeta de sonido.

> Nota de la sesión: en un momento planteé "¿qué pasa si `finished = true` se escribe antes de pedir el candado?" esperando que causara audio viejo. No es así mientras `cancel()` siga esperando el candado antes de retornar. El motivo real de escribirlo dentro es el punto 3.

---

## Lección 3 (en curso). `AiEngine` y la cola de señales

**Archivo:** `ai/AiEngine.java`.

`AiEngine` no reenvía los tokens desde el callback del modelo. Los callbacks sólo hacen `add()` a una `BlockingQueue<Signal>`, y el hilo propio de `AiEngine` los saca con `poll(timeout)`.

### Por qué así y no dentro de `onEvent`

- **Un timeout dentro de un callback sólo se evalúa cuando llega un evento.** Si Gemini se cuelga y nunca manda el primer token, ese código nunca corre.
- **Con `poll(timeout)`, el silencio también es una señal:** si vence el plazo, `poll` devuelve `null` y `AiEngine` cancela, reintenta o dice "Lo siento…".
- **Un solo hilo decide todo** (tokens, errores, fin y timeouts, en orden). No hay dos hilos peleando por "¿fue timeout o llegó el token?". Es el mismo problema del candado, resuelto con una cola.
- **El callback de Gemini sólo hace `add()`**, que es instantáneo, así que respeta la regla de que los listeners retornan rápido.

```
 hilo de Gemini                 BlockingQueue<Signal>            hilo de AiEngine
 onEvent(token) ──── add ────▶  [ Token ][ Token ] ─── poll ───▶ ¿llegó algo a tiempo?
 onComplete()   ──── add ────▶  [ Completed ]                      sí → reenviar / terminar
 onError(e)     ──── add ────▶  [ Failed ]                         no → null → timeout
```

### Pregunta pendiente para la próxima sesión

En el código, el plazo cambia después del primer token:

```java
long wait = sawText ? remaining : Math.min(remaining, firstTokenTimeout);
```

¿Por qué antes del primer token se espera como mucho 8 s, pero después sólo importa el plazo total de 30 s? Piensa en cómo se siente cada caso para quien espera la respuesta, y en qué hace `AiEngine` con un fallo antes y después de haber dicho algo.

---

## Preguntas sueltas de estas sesiones

### ¿Estamos haciendo "loop engineering"?

Sí. El proyecto está montado para que un agente trabaje en un bucle corto, verificable y con memoria (Parte 1 de la spec):

- **Bucle:** siguiente requisito → test → código mínimo → `./mvnw verify` → commit con ID → marcar `PROGRESS.md`.
- **Feedback rápido y determinista:** fakes y simulador de micrófono, sin red ni hardware.
- **Guardarraíles automáticos:** ArchUnit y los tests que vigilan las reglas del prompt.
- **Memoria entre sesiones:** `CLAUDE.md`, `PROGRESS.md`, `IDEAS.md`, los ADR y este documento.
- **Punto de parada:** al final de cada fase, el dueño aprueba.

Wednesday también *es* un bucle en tiempo real (escuchar → pensar → hablar); las Fases 3 y 4 diseñan ese bucle sin carreras.

### ¿Cuál es la parte más difícil del proyecto?

La **Fase 4 (turnos y barge-in)**:

1. **Muchos streams vivos a la vez** (micrófono → STT, LLM, TTS, reproducción). Una interrupción debe parar tres y arrancar otro en menos de 300 ms.
2. **Saber si el usuario habla de verdad** (una tos no debe cortar) y cuándo terminó (una pausa no es el final).
3. **El eco:** Wednesday se oye a sí misma por el micrófono y podría interrumpirse sola. Hay que confiar en la cancelación de eco del navegador y comprobarla con audio real.
4. **Recordar sólo lo que se oyó:** la TTS genera más rápido que el tiempo real, así que "lo generado" va por delante de "lo oído". Hay que seguir la posición de reproducción.

En segundo lugar, la **latencia**: unos 2 s hasta el primer audio frente al objetivo de 1,2 s (Fase 7).

### Qué trae la Fase 3

Conversación de voz completa en el navegador: formato de audio (16 kHz, 16 bits mono, tramas de 20 ms), audio por el WebSocket en ambos sentidos, STT real con parciales y finales, máquina de estados (IDLE → LISTENING → THINKING → SPEAKING), micrófono y reproducción en `index.html`, limpieza al desconectar y un test de integración con un WAV. Queda una decisión para el dueño: el proveedor de STT (Deepgram, Vosk local o investigar la Gemini Live API).

---

## Próximas lecciones

1. **Lección 3 (terminar):** reintentos, disculpa hablada y por qué los fallos no se guardan en el contexto.
2. **Contexto y prompt:** ventana de N turnos, prompt en archivo, tests que vigilan las reglas.
3. **Medición:** `Timeline`, etapas, percentiles y cómo se lee el reporte.
4. **`SpokenReply`:** el pipeline de dos hilos, el divisor en frases y el agrupado para ahorrar cuota.
5. **Gateway y sesión:** ahora cubierto por las lecciones de la Fase 3 (abajo).
6. **Configuración y secretos:** `.env`, MicroProfile Config y por qué la key va en un header.
7. **La estrategia de tests:** fakes, servidores HTTP locales que imitan a Gemini, ArchUnit y tests de carreras.

---

## Lecciones de la Fase 3 (voz de entrada, Gateway y estados)

Mismo formato guiado: una pieza cada vez y una pregunta para predecir antes de ver la respuesta. Las respuestas están aquí, pero mejor intentarlo primero.

### F3-1. Formato de audio fijo en la frontera (ADR 0002)
- **Qué:** el micrófono entra como PCM a 16 kHz, 16 bits y mono, en tramas de 20 ms. `gateway.InputFrames` rechaza cualquier mensaje binario que no mida exactamente 640 bytes.
- **Pregunta:** ¿de dónde sale el número 640? ¿Por qué rechazar en vez de "arreglar" lo que llega?
- **Respuesta:** 16 000 muestras/s × 2 bytes × 0,020 s = 640. Validar en la frontera da a todo lo de dentro una sola forma de audio, y los tests no tienen que negociar formatos. Arreglar en el servidor (remuestrear) cuesta CPU y casos raros. Por eso el navegador hace ese trabajo con un AudioWorklet.

### F3-2. La máquina de estados como tabla
- **Qué:** `SessionStateMachine` tiene un mapa `estado → estados permitidos`. Un movimiento ilegal se rechaza, se registra en el log y no cambia nada. El test recorre los 16 pares posibles.
- **Pregunta:** ¿por qué SPEAKING → LISTENING está prohibido ahora, si es justo lo que quieres hacer al interrumpir?
- **Respuesta:** interrumpir es la Fase 4 (barge-in). Prohibirlo hoy obliga a que entre como un cambio revisado de la tabla, con su test, y no "porque sí". La tabla es la especificación ejecutable.

### F3-3. El adaptador de STT: la documentación contra la realidad
- **Qué:** `GeminiSpeechToText` usa el WebSocket del JDK (sin librerías) y funciona como pulsar para hablar: `setup` (VAD automático apagado), `activityStart`, audio en lotes de 100 ms y `activityEnd`.
- **Historia:** la documentación sugería que el turno acaba con `turnComplete`. Al probarlo contra la API real, ese mensaje nunca llegó. Llegaron `generationComplete`, todo en tramas **binarias**, y el `Final` esperaba al timeout de 3 s. Con el cambio, llega unos 0,4 s después de soltar.
- **Pregunta:** si los tests con el servidor falso pasaban, ¿cómo se nos habría escapado esto?
- **Respuesta:** el servidor falso imitaba la documentación, no la realidad. Un fake solo es tan bueno como lo que imita. Por eso se hace una prueba real pequeña y luego se copia lo observado al fake (ahora el test lleva la secuencia real con fecha).

### F3-4. `VoiceSession` es un actor (ADR 0003)
- **Qué:** un solo hilo por sesión y una cola. Comandos del cliente, transcripciones y eventos de la respuesta entran como mensajes. Los callbacks de los streams **solo encolan**.
- **Pregunta (enlaza con la lección 2):** en la antigua `ChatSession`, `reset()` tenía el lock de la sesión y llamaba a `cancel()`, que pide el candado de entrega del stream. A la vez, `onComplete` se ejecutaba *dentro* del candado de entrega y pedía el lock de la sesión. ¿Qué pasa?
- **Respuesta:** interbloqueo (deadlock). Cada hilo tiene el candado que el otro espera. El actor lo elimina por diseño, porque no hay locks de sesión que ordenar.

### F3-5. Etiquetas por stream, no contadores
- **Historia:** el STT falso emite `Final` y justo después `onComplete`. Con un contador de "generación", ese `onComplete` tardío se tomó por el fin de la *respuesta*, y la sesión saltó de THINKING a IDLE sin hablar. El test `voiceTurnGoesThroughEveryState` lo detectó.
- **Pregunta:** ¿por qué etiquetar cada mensaje con el objeto-stream que lo produjo lo arregla?
- **Respuesta:** la pregunta correcta no es "¿es de este turno?", sino "¿viene del stream que ahora me importa?". Un stream cancelado o terminado deja de ser el actual y sus mensajes se descartan.

### F3-6. Fugas de hilos y el detalle de los hilos virtuales
- **Qué:** `VoiceSocketCleanupTest` conecta y desconecta 12 veces (en reposo, escuchando y a mitad de respuesta) y exige que no quede ni sesión ni hilo.
- **Pregunta:** ¿por qué no basta `Thread.getAllStackTraces()`?
- **Respuesta:** no incluye los hilos virtuales. El volcado JSON de hilos de la JVM sí, y por eso `LiveThreads` lo usa. Además se hizo una "prueba de mutación": quitar `session.close()` hace fallar el test. Un test que nunca puede fallar no prueba nada.

### F3-7. El navegador: capturar y reproducir audio
- **Qué:**
  - Un AudioWorklet remuestrea de 48 kHz a 16 kHz con interpolación lineal y corta tramas de 320 muestras.
  - La voz de 24 kHz se programa en bloques seguidos (`nextAt`).
  - El piloto sigue en "Hablando" hasta que el navegador termina de sonar.
- **Pregunta:** el servidor dice IDLE en cuanto envía el último audio. ¿Por qué el navegador no le hace caso enseguida?
- **Respuesta:** la voz se genera más rápido que el tiempo real, así que el servidor termina de enviar antes de que acabe de sonar. El estado que ve el usuario es el de lo que *oye*. En la Fase 4 esto importará para interrumpir.

### Lección de proceso (un error mío)
El commit de P3-R6 no compilaba. Filtré la salida de Maven con `grep` y la línea del error no coincidía con el filtro. Desde entonces miro siempre el **código de salida** del comando, no solo su texto, y lo corregí en un commit aparte. Lo aplicable a cualquier pipeline de CI: un filtro de logs nunca sustituye al código de salida.

### Números de la Fase 3 (Gemini real)
| Medida | Valor |
|---|---|
| Transcripción final tras soltar | ~0,4–0,5 s |
| Primer audio tras soltar (cliente Java) | 2,66 s |
| Primer audio tras soltar (Chrome con micro falso) | 2,01 s |
| Objetivo p50 | 1,2 s → trabajo de la Fase 7 |

