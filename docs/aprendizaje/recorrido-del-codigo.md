# Recorrido por el código de Wednesday

Notas de las sesiones de recorrido guiado: qué hace cada pieza, qué patrones usa y qué decisiones de diseño de sistemas hay detrás. Se escribe a medida que avanzamos; al final de cada sesión queda la pregunta pendiente para retomar.

- Recurso interactivo de la lección 2 (simulador de la carrera entre hilos): https://claude.ai/artifact/NW8saFpaQ7uUx86dhQq36K
- Estado del proyecto al escribir esto: Fases 0, 1 y 2 terminadas (103 tests). Siguiente: Fase 3.

---

## Mapa general

```
                 CLIENTES (cómo entras)
   ┌──────────────────────┐        ┌──────────────────────────┐
   │ cli/ChatCli (+voz)   │        │ index.html (navegador)   │
   └──────────┬───────────┘        └────────────┬─────────────┘
              │                                 │ WebSocket /ws/chat
              │                     ┌───────────▼─────────────┐
              │                     │ gateway/ChatSocket      │  sólo traduce JSON
              │                     └───────────┬─────────────┘
              ▼                                 ▼
   ┌─────────────────────────────────────────────────────────┐
   │ session/   ChatSession · SpokenReply · ChatSessions     │  orquesta
   └───────┬──────────────────┬──────────────────┬───────────┘
           ▼                  ▼                  ▼
   ┌──────────────┐   ┌──────────────┐   ┌────────────────────┐
   │ ai/AiEngine  │──▶│ context/     │   │ tts/SentenceChunker│
   │ (reintentos, │   │ Conversation │   │ tts/TextToSpeech   │
   │  timeouts)   │   │ Context      │   └─────────┬──────────┘
   └──────┬───────┘   └──────────────┘             │
          ▼ interfaz LanguageModel                 ▼ interfaz TextToSpeech
   ┌──────────────────────┐              ┌──────────────────────┐
   │ ai.provider.gemini   │  ← sólo aquí │ tts.provider.gemini  │
   │ ai.fake              │    se habla  │ tts.fake             │
   └──────────────────────┘    con Google└──────────────────────┘

   common/  ← lo compartido: StreamListener/StreamHandle, VirtualThreadStream,
              AudioChunk, PcmFormat, SessionId, Timeline   (no depende de nadie)
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
5. **Gateway y sesión:** por qué el gateway no tiene lógica y cómo vive una sesión por conexión.
6. **Configuración y secretos:** `.env`, MicroProfile Config y por qué la key va en un header.
7. **La estrategia de tests:** fakes, servidores HTTP locales que imitan a Gemini, ArchUnit y tests de carreras.
