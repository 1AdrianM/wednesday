# Progress
Current phase: 0

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

## Phase 1
- [ ] P1-R1 … P1-R7 (see docs/SPEC.md)

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
