# Latency breakdown of a voice turn (2026-11-22)

Real providers, all on the free tier: Gemini Transcribe Live for STT, `gemini-3.5-flash-lite` for the LLM, `gemini-3.8-flash-lite-tts` for TTS. Eight push-to-talk turns from the es/en fixture WAVs. All times are in ms after the end of speech.

| turn | transcript final | first token | first audio | notes |
|---|---|---|---|---|
| 1 | 3004 | 7956 | – | STT returned "" (no generationComplete within the 3 s timeout); LLM first token 4,9 s |
| 2 | 4512 | 5042 | 7455 | STT returned ""; LLM 400 (see below), so the spoken apology played |
| 3 | 7198 | 8244 | 9683 | STT 7,2 s |
| 4 | 446 | 1212 | 2448 | healthy turn: STT 0,45 + LLM 0,77 + TTS 1,24 |
| 5 | 2357 | 3930 | – | TTS 429 |
| 6 | 1000 | 1636 | – | TTS 429 |
| 7 | 334 | 786 | – | TTS 429 |
| 8 | 718 | 1524 | – | TTS 429 |

Earlier runs the same day measured first audio at 2,01 s (Chrome) and 2,66 s (Java client).

## Per-stage cost
| Stage | Healthy | Tail |
|---|---|---|
| STT: end of speech to final transcript | 0,33–0,72 s | 2,4–7,2 s; one connect timeout (10 s) in an earlier run |
| LLM: request to first token | 0,45–0,80 s | 1,0–1,6 s, one 4,9 s outlier |
| Chunker: first token to first segment | ~0 s (short replies fit in one sentence) | grows with a long first sentence |
| TTS: first segment to first audio | 1,2–1,4 s | 2,4 s |

**Steady state:** TTS is the largest stage. **Tail (p95):** STT variance dominates (the Live WebSocket connection and setup are opened per turn, at press time), followed by LLM outliers.

## Blockers found
- **TTS free-tier quota.** `gemini-3.8-flash-lite-tts` allows 10 requests per day and 3 per minute. It ran out during this measurement, so voice falls back to text until the quota resets.
- **Blank transcript reaching the LLM.** In this harness (not in `VoiceSession`, which filters blanks), a blank user text produced Gemini 400 "Requests ending with a model turn are not supported". `AiEngine.respond` should reject blank text itself.

## Candidate fixes (Phase 7, not done)
1. **STT:** keep a warm Live connection per session, or connect when the session opens, so a press does not pay the handshake. Retry the connect once.
2. **TTS:** a faster first audio. Make the first segment a short clause (cut at the first comma). Try a TTS with lower time-to-first-byte or a paid tier. Cache fixed phrases such as the apology.
3. **LLM:** start speculatively on a stable partial transcript. Keep thinking minimal. Measure a model with lower first-token latency.
4. **Measure p50/p95 over 20+ turns** once the quota allows. Eight turns with failures are only indicative.

## Owner's browser test (2026-11-23)
Speaking into the browser UI: text appears ~1,3 s after release, first audio ~3 s. The ~1,7 s gap is the first sentence plus a non-streaming TTS call, consistent with the TTS row above. Candidate fix 2 (shorter first segment) is the cheapest next step.
