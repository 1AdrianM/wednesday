# Progress
Current phase: 1

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
- [ ] P1-R4 AiEngine.respond(userText)
- [ ] P1-R5 Timeouts, one retry, spoken error message
- [ ] P1-R6 CLI chat loop
- [ ] P1-R7 Tests: trimming, error path, language switch

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
