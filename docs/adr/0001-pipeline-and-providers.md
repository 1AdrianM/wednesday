# 0001. Cascaded pipeline and provider shortlist

- Status: **Proposed**. The architecture part is fixed by the spec. The provider picks need the owner's confirmation before Phase 1 (LLM), Phase 2 (TTS) and Phase 3 (STT) start.
- Date: 2026-11-22

## Context

Wednesday is a personal voice assistant. It speaks Spanish first and English second. The targets are first audio in under 1.2 s at p50 (2.5 s at p95) and barge-in in under 300 ms. Every provider sits behind our own interface (`SpeechToText`, `LanguageModel`, `TextToSpeech`) and has a fake, so swapping one later costs one adapter package. The legacy Spring scaffold already used Google GenAI (Gemini), ElevenLabs and whisper.cpp.

## Decision 1: cascaded pipeline, not speech-to-speech

| | Cascaded (STT → LLM → TTS) | Speech-to-speech (one realtime model, e.g. OpenAI Realtime, Gemini Live) |
|---|---|---|
| Latency | Three hops. Streaming every stage plus sentence chunking can still meet the target. | Lowest. One hop, and the model hears tone and timing. |
| Control | Full. We see the text at every step, so we can apply spoken-style rules, check tool calls, enforce the memory write policy and log transcripts. | Mostly inside the provider. Tools and turn-taking follow its rules. |
| Testability | Each stage has a fake, so tests are deterministic without audio hardware. | Hard to fake faithfully. Tests need real audio and a paid API. |
| Provider lock-in | Per stage. Each part can be swapped on its own. | The whole voice experience is one vendor. |
| Learning value (a v1 goal) | High. We build turn-taking, barge-in and streaming ourselves. | Low. The interesting parts are hidden. |

**Decision:** cascaded pipeline, as in the original Jarvis note and the spec scope. Speech-to-speech goes in `IDEAS.md` as a later comparison experiment.

## Decision 2: provider shortlist

Hard requirements for every stage: Spanish and English, streaming output, and an API we can call with a plain HTTP or WebSocket client (project rule: no heavy SDKs unless a phase needs one).

### STT (streaming, with partial and final transcripts)

| Option | Notes |
|---|---|
| **Deepgram** (proposed) | Streaming WebSocket API with interim (partial) and final results, endpointing hints, Spanish and English. A plain WebSocket fits the project rule. |
| Google Cloud Speech-to-Text | Good Spanish. Streaming uses gRPC, which brings a heavier client stack. |
| AssemblyAI streaming | WebSocket streaming. Check language coverage for Spanish streaming before choosing it. |
| whisper.cpp (local, legacy) | Free and offline, but no native streaming partials. Needs a chunk-and-re-decode loop and a JNI build. Worth keeping as an offline fallback idea. |

### LLM (streaming tokens, tool calls)

| Option | Notes |
|---|---|
| **Google Gemini, Flash tier** (proposed) | Already used by the legacy code, so the owner likely has access. Fast tier, streaming over HTTP (SSE), tool calling, strong Spanish. |
| Anthropic Claude (Haiku/Sonnet tier) | Streaming over HTTP (SSE), tool use, strong Spanish. A good candidate if Gemini time-to-first-token or spoken-style compliance disappoints. |
| OpenAI (small/fast tier) | Streaming and tool calling. Comparable option. |

### TTS (streaming audio chunks)

| Option | Notes |
|---|---|
| **ElevenLabs** (proposed) | Already used by the legacy code. Streaming endpoints (HTTP chunked and WebSocket), natural multilingual voices including Spanish, can return raw PCM. |
| Google Cloud Text-to-Speech | Solid Spanish voices. Streaming synthesis is more limited and uses gRPC. |
| Cartesia | Built for low-latency streaming over WebSocket. Check Spanish voice quality before choosing it. |

**Proposed picks:** Deepgram (STT), Gemini Flash tier (LLM), ElevenLabs (TTS).
**Reason:** the two legacy choices (Gemini, ElevenLabs) are kept because they already meet every requirement. Deepgram replaces whisper.cpp because the turn-taking design (Phases 3 and 4) needs real streaming partials.

## Consequences

- Each pick is checked against the latency targets in Phase 7. A provider that misses badly gets replaced by the next one on its shortlist, which costs one adapter.
- API keys come only from environment variables, and are never logged.
- The audio format (Phase 3 ADR) should match what the STT accepts and the TTS can output natively (16 kHz 16-bit PCM is supported on both proposed sides), so no resampling is needed.
