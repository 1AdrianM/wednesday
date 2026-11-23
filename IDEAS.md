# Ideas (not in the current phase)

Parking lot for anything outside the current phase. One line each; move into the spec when a phase picks it up.

- Wake word.
- Multiple voices.
- Calendar and email tools.
- Mobile client.
- Speech-to-speech model experiment for comparison with the cascaded pipeline.
- IoT control as tools (after Phase 5): Home Assistant local REST/WebSocket adapter in `tools.provider.homeassistant`; per-device risk levels (low: act and say it; high, e.g. locks/garage/oven: spoken confirmation); allow-list of entities; token in `.env`.
- Proactive speech from device events ("the garage door is open"): needs an unsolicited-speech state in the session machine.
- ESP32 voice satellites per room as `/ws/voice` clients (16 kHz PCM frames already fit); needs the wake word.
- Self-hosted stack (Mac mini 24–32 GB+, or a GPU VPS): one `ai.provider.openaicompat` adapter covers Ollama, llama.cpp, MLX and vLLM. Candidates to test with our own tool schemas: Qwen3.5-9B, gpt-oss-20b, Gemma 4 (E4B minimum, 27B), Qwen3.6-35B-A3B. Local STT (whisper.cpp) and TTS (Piper/Kokoro) would remove the cloud TTS quota. Hybrid routing: local for the fast path, cloud for hard questions.
