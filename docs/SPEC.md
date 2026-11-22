# Wednesday: Realtime Voice Intelligence (formerly Jarvis AI)

Spec for building Wednesday phase by phase with a coding agent.
Replaces the original Jarvis architecture note; its component model is kept, with the gaps filled in.

---

## Part 1. Rules for the coding agent

Mirrored in [`CLAUDE.md`](../CLAUDE.md), which is the copy agents read.

---

## Part 2. What to improve from the original Jarvis note

| Gap in the original | Why it matters | Where it is handled |
|---|---|---|
| No **turn-taking / barge-in** design | This is the hardest part of voice. If the user interrupts, TTS and the LLM call must stop at once. | Phase 4 |
| No **session state machine** | Without explicit states (LISTENING, THINKING, SPEAKING, INTERRUPTED) the loop turns into race conditions. | Phase 3 |
| Latency is a goal but has **no numbers or measurement** | What is not measured does not improve. | Phase 0 (tracing), Phase 7 (budget) |
| No audio format decision | Sample rate, encoding and frame size touch every component. | Phase 3 |
| Tool calls do not say what the user **hears while waiting** | Silence for 3 seconds feels like a crash. | Phase 5 |
| No confirmation rule for tools with side effects | A misheard "delete" must not execute. | Phase 5 |
| Memory is described but has no **write policy** | "Selective and purposeful" needs a concrete rule or it saves everything. | Phase 6 |
| No **voice-specific response rules** | Chat answers (lists, markdown, long text) sound bad spoken. | Phase 1 |
| No way to **test without a microphone** | Agent loops need fast, deterministic checks. | Phase 0 |
| Cascaded pipeline vs a single speech-to-speech model not recorded as a decision | Both are valid; the choice shapes everything. | ADR in Phase 0 |
| Primary use case not chosen (personal assistant vs business receptionist vs phone agent) | Changes the client, latency target and tools. | Scope decision below |

### Scope decisions (fixed for v1)
- **Use case:** personal voice assistant for the owner, built for fun and learning.
- **Client:** browser or small desktop client with microphone and speakers. Telephony is out of scope until Phase 7 and optional.
- **Architecture:** cascaded pipeline (STT → AI → TTS), as in the original note. A speech-to-speech model is recorded as an alternative in the Phase 0 ADR, not built.
- **Language:** Spanish first, English second. Provider choices must support both.
- **No** multi-user, accounts, billing or generic agent platform.

### Initial latency targets (tune after measuring in Phase 7)
- Time to first audio byte after the user stops speaking: p50 under 1.2 s, p95 under 2.5 s.
- Barge-in: audio output stops within 300 ms of detected user speech.

---

## Part 3. Requirements by phase

Each requirement has an ID, is testable, and fits one loop iteration. Acceptance checks are what the human runs to approve the phase.

### Phase 0. Skeleton, fakes and measurement
**Goal:** a project where every later phase can be developed and tested without audio hardware or paid APIs.

- P0-R1: Quarkus project on Java 21 builds with `./mvnw verify`; a health endpoint returns 200.
- P0-R2: Package layout per component (`gateway`, `stt`, `ai`, `context`, `memory`, `tools`, `tts`, `session`) with an architecture test that fails if a package imports a provider type from another component.
- P0-R3: Interfaces defined: `SpeechToText` (streaming, partial and final events), `LanguageModel` (streaming tokens, tool-call events), `TextToSpeech` (streaming audio chunks).
- P0-R4: Fake implementation of each interface, with configurable delay and canned output.
- P0-R5: Each session gets an ID that appears in every log line.
- P0-R6: A `Timeline` utility records timestamped stage events per turn (speech end, first token, first audio, etc.) and can print a latency report.
- P0-R7: Test audio fixtures (short WAV files in Spanish and English) and a helper that streams them as if from a microphone.
- P0-R8: ADR 0001 records: cascaded vs speech-to-speech, and the shortlist of STT/LLM/TTS providers with the reason for the pick.
- P0-R9: `PROGRESS.md` and `IDEAS.md` exist.

**Acceptance:** `./mvnw verify` green; running the sample test prints a latency report with the fake providers.

### Phase 1. Text brain (no audio)
**Goal:** prove the intelligence and conversation flow in text before adding voice.

- P1-R1: Real `LanguageModel` adapter behind the interface, key read from environment, streaming tokens.
- P1-R2: `Context` keeps the last N turns (configurable) and builds the model input; includes a system prompt loaded from a file.
- P1-R3: System prompt enforces **spoken style**: short answers, no markdown, no lists, numbers and symbols written to be read aloud, Spanish by default and English if the user switches.
- P1-R4: `AiEngine.respond(userText)` returns a token stream and updates Context with both turns.
- P1-R5: Timeouts and one retry on provider failure; on final failure it returns a short spoken-friendly error message.
- P1-R6: CLI chat loop to talk to it in text.
- P1-R7: Tests with the fake model cover: context window trimming, error path, language-switch prompt handling.

**Acceptance:** a 10-turn text conversation in the CLI keeps context correctly; answers are short and speakable.

### Phase 2. Voice out (TTS) and sentence streaming
**Goal:** hear Wednesday speak, starting before the full answer exists.

- P2-R1: Real `TextToSpeech` adapter behind the interface, streaming audio chunks.
- P2-R2: A sentence chunker turns the token stream into speakable segments (flush at sentence end or after a maximum length) and handles abbreviations and decimals.
- P2-R3: Pipeline: model tokens → chunker → TTS, running concurrently; the first segment goes to TTS as soon as it is complete.
- P2-R4: Local playback in the CLI client.
- P2-R5: `Timeline` records time from request to first audio; the report shows it per turn.
- P2-R6: Test with fakes that audio for segment 1 starts before the model finishes.

**Acceptance:** typed question → spoken answer starting while the text is still generating; latency report shows first-audio time.

### Phase 3. Voice in (STT), Gateway and session state machine
**Goal:** a full voice turn end to end.

- P3-R1: Audio format decision recorded (suggested: 16 kHz, 16-bit mono PCM, 20 ms frames) and enforced at the Gateway.
- P3-R2: Gateway exposes a WebSocket: client sends audio frames and control messages; server sends audio frames and events (transcript, state).
- P3-R3: Real `SpeechToText` adapter with partial and final transcripts.
- P3-R4: Session state machine with states IDLE, LISTENING, THINKING, SPEAKING and explicit allowed transitions; illegal transitions are rejected and logged. Tested exhaustively with a table test.
- P3-R5: Minimal browser client (single HTML file): capture microphone, play audio, show state and transcript.
- P3-R6: The Gateway contains no prompts or logic; an architecture test enforces it.
- P3-R7: Session cleanup on disconnect (no leaked threads or provider streams); test with repeated connect/disconnect.
- P3-R8: Integration test streams a fixture WAV through the WebSocket with fake providers and expects a response audio stream.

**Acceptance:** speak into the browser, get a spoken answer; the state indicator follows the real flow.

### Phase 4. Turn-taking and barge-in
**Goal:** it feels like a conversation, not walkie-talkie.

- P4-R1: End-of-utterance detection (silence threshold plus final transcript), configurable and with a sensible default.
- P4-R2: Voice activity detection while SPEAKING: if the user starts talking, the session moves to INTERRUPTED.
- P4-R3: On interruption: stop TTS generation, stop sending audio, cancel the in-flight model call, within the 300 ms target (measured in a test with fakes).
- P4-R4: The interrupted assistant turn is stored in Context marked as cut off, with only the text that was actually spoken.
- P4-R5: Ignore very short noises (configurable minimum duration) so a cough does not cancel a response.
- P4-R6: Handling of the user speaking again right after finishing (merge with the previous utterance instead of two turns).
- P4-R7: Tests: interruption at start, middle and end of a response; rapid double interruption; interruption during THINKING.

**Acceptance:** you can interrupt Wednesday mid-sentence and it stops and answers the new request.

### Phase 5. Tools
**Goal:** Wednesday can do things, safely and without awkward silence.

- P5-R1: Tool contract: name, description, JSON-schema arguments, result type, timeout, `sideEffects` flag.
- P5-R2: Argument validation before execution; invalid calls return a structured error to the model instead of throwing.
- P5-R3: Execution with timeout and error handling; the model always receives a result or a structured error.
- P5-R4: Tool loop in the AI engine with a maximum number of tool calls per turn.
- P5-R5: While a tool runs longer than a threshold, Wednesday says a short filler phrase once; interruption cancels the tool when possible.
- P5-R6: Tools with `sideEffects=true` require spoken confirmation ("do you want me to…?") before execution; the confirmation is a state in the session, tested with yes, no and unclear answers.
- P5-R7: Three starter tools: current date/time, create a note, read the latest notes. More are added later, one at a time.
- P5-R8: Every tool call is logged with arguments and result at INFO (no secrets).
- P5-R9: Tests: invalid args, timeout, tool error, confirmation flow, call limit.

**Acceptance:** "save a note that…" asks for confirmation, saves it, and "read my notes" reads it back.

### Phase 6. Memory
**Goal:** it remembers what matters across sessions, and nothing else.

- P6-R1: `Memory` interface with `store`, `retrieve(query, limit)`, `forget`; first implementation is a local file or embedded database.
- P6-R2: Write policy in code and documented: store only facts the user states about themselves, preferences, and explicit "remember this" requests. Do not store small talk or raw transcripts.
- P6-R3: End-of-session step produces a short summary and candidate memories; writes pass through the policy.
- P6-R4: Retrieval only when the AI decides it is needed (a retrieval tool or a gate), not on every turn; test that a plain greeting triggers no retrieval.
- P6-R5: Voice commands to list and forget memories ("what do you remember about…", "forget that").
- P6-R6: Retrieved memories enter Context labeled as such and never override the current instruction.
- P6-R7: Tests with the fake model for the policy: what is stored, what is skipped, forget removes it completely.
- P6-R8 (`// HUMAN`): the retrieval ranking logic is written by the human; the agent provides the interface and tests only.

**Acceptance:** tell it a preference in one session; a later session uses it correctly; "forget it" removes it.

### Phase 7. Hardening and latency
**Goal:** reliable and fast enough to use daily.

- P7-R1: Latency report across 20 scripted turns with real providers; results saved in `docs/latency.md` and compared with the targets.
- P7-R2: Optimize the biggest stage first with before and after numbers; repeat once.
- P7-R3: Reconnection: if the WebSocket drops, the client reconnects and resumes the session within a time limit.
- P7-R4: Provider failure behavior: STT, LLM and TTS each have a defined fallback or a spoken apology, tested with failing fakes.
- P7-R5: Cost log per session (tokens, audio seconds) and a configurable daily limit.
- P7-R6: README with architecture diagram, how to run, latency results and known limits.
- P7-R7 (optional): telephony client adapter behind the Gateway, only if a real use appears.

**Acceptance:** a 30-minute real session without restarts; latency doc updated.

---

## Later ideas (not in v1)
Wake word, multiple voices, calendar and email tools, a mobile client, speech-to-speech experiment for comparison.
