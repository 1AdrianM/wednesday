# 0003. The voice session is an actor; Phase 3 is push-to-talk

- Status: Accepted
- Date: 2026-11-22

## Context

A voice session receives input from three sources at once. The client sends controls and 50 audio frames per second. The STT stream sends partial and final transcripts. The reply stream sends text, audio and its end. All of them change the same state: the state machine, the current turn and the open streams.

The text-only `ChatSession` guarded its state with `synchronized`. That design has a lock-order deadlock:

1. `reset()` holds the session lock and calls `cancel()` on the reply stream, which waits for that stream's delivery lock.
2. At the same moment, the stream's `onComplete` runs inside that delivery lock and waits for the session lock.

## Options

1. **Locks with a strict order.** Never call into a stream while holding the session lock. This is easy to break with any later change, and hard to test.
2. **Actor.** One thread per session owns all the state. Every input, whether a client command or a stream callback, becomes a message in one queue. Callbacks only enqueue, so they never block while holding a stream's delivery lock. The cost is one virtual thread per session, and every message passes through a queue.
3. **Reactive pipeline (Mutiny or Reactor operators).** This is powerful, but it adds a framework style the project avoids, and it makes it harder for the owner to see the flow.

## Decision

Option 2. `VoiceSession` is an actor, the same pattern `AiEngine` and the Gemini STT adapter already use internally. Each stream's messages carry a tag naming that stream. A message from a stream that is no longer current (cancelled, finished or reset) is dropped.

This solved a real bug while it was being built. The fake STT emits its final transcript and then its completion. A plain "generation" counter could not tell that late completion apart from the end of the reply. Tagging by stream identity can.

**Turn-taking in Phase 3 is push-to-talk.** The client sends `start`, then frames, then `stop`, and the STT runs with automatic activity detection disabled. End-of-utterance detection and barge-in are Phase 4 (P4-R1, P4-R2), so the state table does not allow SPEAKING to LISTENING yet.

`ChatSession` and `/ws/chat` were retired once `/ws/voice` also handled typed text.
