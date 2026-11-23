# 0002. Audio format at the Gateway

- Status: Accepted
- Date: 2026-11-22

## Context

The browser captures microphone audio and sends it over the WebSocket to the STT provider, which is Gemini Transcribe Live (`gemini-3.5-transcribe-live`). That model expects raw 16-bit PCM at 16 kHz, mono, little-endian. Gemini TTS replies with 24 kHz mono PCM. A single fixed input format keeps the Gateway trivial and lets tests build frames without guessing.

## Options

1. **16 kHz, 16-bit mono PCM, 20 ms frames (640 bytes), enforced strictly.** It matches the STT exactly, so nothing needs converting on the server. 20 ms is the usual VAD and telephony frame size, which gives us fine-grained timing for barge-in in Phase 4. The cost is that the browser must resample, because it captures at 44.1 or 48 kHz, but an AudioWorklet does that in a few lines.
2. **Accept any rate and resample on the server.** The client gets simpler, but the server pays for DSP and edge cases (odd rates, stereo), and every test needs a format negotiation step.
3. **Compressed audio (Opus or WebM).** It needs less bandwidth, but the server has to decode it, which adds a native library and latency. That is not worth it on localhost or a home network.

## Decision

Option 1. Each binary WebSocket message from the client is exactly one 640-byte frame, and the Gateway rejects any other size with an error event (`gateway.InputFrames`). The constants live in `common.AudioFormats`.

Server-to-client audio is not resampled. It leaves in the TTS format (24 kHz mono 16-bit PCM), and that format is announced to the client in the session's `ready` event.
