# Progress
Current phase: 3

## Phase 0
- [x] P0-R1 Quarkus skeleton + health endpoint
- [x] P0-R2 Component packages + architecture test
- [x] P0-R3 SpeechToText / LanguageModel / TextToSpeech interfaces
- [x] P0-R4 Fakes with configurable delay and canned output
- [x] P0-R5 Session ID in every log line
- [x] P0-R6 Timeline + latency report
- [x] P0-R7 WAV fixtures (es/en) + microphone simulator
- [x] P0-R8 ADR 0001 (architecture + providers)
- [x] P0-R9 PROGRESS.md and IDEAS.md

Acceptance: `./mvnw verify` green; `./mvnw test -Dtest=SampleTurnTest` prints the latency report. Approved implicitly: owner asked to proceed to a functional client (2026-11-22).

## Phase 1
- [x] P1-R1 Gemini adapter (streaming SSE, key from env)
- [x] P1-R2 Context with last N turns + system prompt from file
- [x] P1-R3 Spoken-style system prompt
- [x] P1-R4 AiEngine.respond(userText)
- [x] P1-R5 Timeouts, one retry, spoken error message
- [x] P1-R6 CLI chat loop
- [x] P1-R7 Tests: trimming (ConversationContextTest), error path (AiEngineFailureTest), language switch (AiEngineLanguageTest)

Acceptance: 10-turn real Gemini conversation (2026-11-22) kept context (name, city, colour, earlier result), switched es->en->es, numbers spoken in words, no lists. First token p50 0,65 s (target 1,2 s) but p95 4,2 s (target 2,5 s): three ~4 s outliers. Approved by owner 2026-11-22.

## Phase 2
- [x] P2-R1 Real TextToSpeech adapter (streaming)
- [x] P2-R2 Sentence chunker (sentence end / max length, abbreviations, decimals)
- [x] P2-R3 Pipeline tokens -> chunker -> TTS, concurrent
- [x] P2-R4 Local playback in the CLI
- [x] P2-R5 Timeline first-audio per turn in the report
- [x] P2-R6 Test: segment 1 audio starts before the model finishes

Acceptance run (2026-11-22, real Gemini text + voice, CLI): turn 1 first token 0,95 s, first audio 2,06 s, text complete 3,66 s (voice started before the text finished); turn 2 first audio 4,06 s (slow first token 2,65 s). Approved by owner 2026-11-22 ("seguimos con las fases").

## Phase 3
STT provider: Gemini Transcribe Live (`gemini-3.5-transcribe-live`), chosen by owner 2026-11-22.
- [x] P3-R1 Audio format decided (ADR 0002: 16 kHz 16-bit mono, 20 ms = 640-byte frames) and enforced at the Gateway (`InputFrames`)
- [x] P3-R2 Gateway WebSocket `/ws/voice`: binary frames + JSON controls in, binary PCM + JSON events out. `VoiceSession` is an actor (one thread + queue); found a latent lock-order deadlock in `ChatSession` (reset vs onComplete), to retire with it in R5
- [x] P3-R3 Real SpeechToText adapter (partial + final). Real API sends binary JSON frames and ends a manual turn with `generationComplete`, never `turnComplete` (docs imply the latter); Final ~0,4 s after end of audio
- [x] P3-R4 Session state machine IDLE/LISTENING/THINKING/SPEAKING, table test (done before R2/R3: the voice socket builds on it). Phase 3 is push-to-talk; SPEAKING->LISTENING (barge-in) waits for Phase 4
- [ ] P3-R5 Minimal browser voice client
- [ ] P3-R6 Architecture test: Gateway holds no prompts or logic
- [ ] P3-R7 Session cleanup on disconnect (repeated connect/disconnect test)
- [ ] P3-R8 Integration test: fixture WAV through the WebSocket with fakes -> response audio

## Phase 6
- P6-R8 // HUMAN — retrieval ranking logic (agent writes interface + tests only)

## Notes
(one line per surprise or decision)
- Legacy Spring Boot/Gradle scaffold replaced by Quarkus/Maven; it is preserved in the first git commit.
- Provider adapters must live in `<component>.provider.<name>`; ArchUnit enforces it. Fakes live in `<component>.fake` (not providers).
- Streams use our own listener/handle contract on virtual threads (no Mutiny in interfaces) so cancel-for-barge-in stays simple.
- Timeline offsets are measured from the earliest mark in each turn (SPEECH_END for voice, REQUEST_SENT for text).
- Audio fixtures are espeak-ng synthetic speech (robotic, but real phonemes); swap for recorded voice if a real STT struggles with them.
- ADR 0001 provider picks are Proposed (Deepgram / Gemini Flash / ElevenLabs); owner must confirm before Phase 1.
- Owner chose Gemini via AI Studio key (free tier). Default model alias `gemini-flash-latest`, thinkingLevel MINIMAL; both overridable by env.
- ChatMessage/ModelRequest moved to common so context does not depend on ai (avoids ai<->context cycle).
- Gemini cancel is checked between SSE lines; a blocked read is not interrupted. Revisit for barge-in in Phase 4.
- Retry only when a call fails before any text (retrying after partial text would repeat words aloud). Apology messages are Spanish only for now.
- CLI is a plain Java main run via exec-maven-plugin (`./chat`), not Quarkus command mode, to keep the web app untouched. Model chosen by name in ai.LanguageModels so cli never imports provider types.
- Owner asked for a browser frontend during Phase 1: added a text-only WebSocket `/ws/chat` (gateway.ChatSocket -> session.ChatSession). It is an early, text-only slice of P3-R2; audio frames and the real state machine still belong to Phase 3.
- Browser client: live-subtitle direction chosen by owner (see DESIGN.md, .impeccable/surfaces/chat.md). Run with `WEDNESDAY_LLM=fake ./mvnw quarkus:dev` or with GEMINI_API_KEY set.
- Default model is gemini-3.5-flash-lite: gemini-flash-latest rejected thinkingLevel MINIMAL and later returned 503 (high demand); gemini-3.6-flash did not answer within 8 s. 3.5-flash-lite gave ~0,4-1,0 s first token.
- 400 errors (bad parameter) are retried like any other failure; not retrying 4xx except 429 is a cheap later improvement (Phase 7, P7-R4).
- Prompt: no closing questions or offers unless a fact is missing (owner found them tiring in the 10-turn run).
- Owner chose Gemini TTS (same AI Studio key) over ElevenLabs. Docs: TTS goes through the Interactions API (/v1beta/interactions, stream:true, PCM s16le 24 kHz mono).
- Real TTS probe: gemini-3.8-flash-lite-tts first audio ~1,3 s after request, synthesis faster than real time (3,5 s audio in ~0,5 s). Voice first-audio ≈ LLM first sentence + 1,3 s, so ~2 s: above the 1,2 s target; Phase 7 material.
- Text and voice share the same free Gemini quota; segments are batched (first sentence alone, then larger groups) to limit TTS calls per reply. Watch for 429s.
