# Wednesday — rules for the coding agent

Full spec (phases, requirements, acceptance checks): [`docs/SPEC.md`](docs/SPEC.md).
Current state: [`PROGRESS.md`](PROGRESS.md). Out-of-scope ideas: [`IDEAS.md`](IDEAS.md).

## Project facts
- Java 21, Quarkus, Maven (`./mvnw`). Virtual threads for blocking I/O.
- Components: Gateway, STT, AI, Context, Memory, Tools, TTS. Each lives in its own package and talks to others only through interfaces.
- Every external provider (LLM, STT, TTS) sits behind an interface with a **fake implementation** used in tests.
- Prefer plain HTTP/WebSocket clients behind our own interfaces over heavy frameworks. Add a library only when a phase needs it, and say why in the commit.

## Code layout conventions
- Base package `com.github.wednesday`. Component packages: `gateway`, `stt`, `ai`, `context`, `memory`, `tools`, `tts`, `session`. Shared primitives (audio chunk, stream handle/listener, session id, timeline) live in `common`.
- Real provider adapters go in `<component>.provider.<name>` (e.g. `stt.provider.deepgram`). Nothing outside that component may import them — enforced by `ArchitectureTest`.
- Fakes go in `<component>.fake`. They are in main sources so tests, the CLI and integration runs can use them.
- Streaming contract (`common.StreamListener` / `common.StreamHandle`): events arrive on a background (virtual) thread; after `cancel()` no further callbacks are delivered; exactly one of `onComplete`/`onError` ends a stream that was not cancelled.
- Logging: `org.jboss.logging.Logger`. Wrap session work in `SessionLogContext.open(sessionId)`; background streams started via `common.VirtualThreadStream` inherit the session id automatically.
- Test-only helpers (WAV microphone simulator, etc.) live in `src/test/java/com/github/wednesday/testing`. Audio fixtures are in `src/test/resources/audio`.

## The loop (follow for every task)
1. Read the current phase section in `docs/SPEC.md` and `PROGRESS.md`.
2. Pick the **next unchecked requirement** (one at a time).
3. Write or extend a test that fails for that requirement.
4. Implement the smallest change that makes it pass.
5. Run `./mvnw verify`. If red, fix before doing anything else. Never skip or delete a test to get green.
6. Commit with the requirement ID in the message (e.g. `P2-R3: sentence chunker`).
7. Tick the requirement in `PROGRESS.md` and note anything surprising in one line.
8. Repeat. When all requirements and acceptance checks of the phase pass, **stop** and report; do not start the next phase.

## Guardrails
- Do not add features outside the current phase. Put ideas in `IDEAS.md` instead.
- No business logic or prompts in the Gateway.
- No provider-specific types outside that provider's adapter package.
- Never log raw audio or API keys. Transcripts only at DEBUG level.
- If a requirement is ambiguous or two readings lead to different designs, ask the human before coding.
- Record design decisions with real trade-offs in `docs/adr/NNNN-title.md` (short: context, options, decision).
- Core code the human wants to write himself is marked `// HUMAN` in `PROGRESS.md`; do not write it, only scaffold the interface and tests.

## Definition of done (any requirement)
Test exists, `./mvnw verify` green, no new warnings, requirement ticked in `PROGRESS.md`.
