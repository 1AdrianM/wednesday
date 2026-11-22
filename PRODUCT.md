# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users
One person: the owner, who builds and uses Wednesday as a personal assistant, for fun and to learn how realtime voice systems work. Uses it on a desktop PC browser, in a window next to other work. No other users, accounts or roles.

## Product Purpose
Wednesday is a realtime voice assistant (formerly "Jarvis AI"). The owner talks to it and it answers with short spoken replies, in Spanish by default and English when the owner switches. Success means it feels like a conversation, not a walkie-talkie: fast first audio, interruptible, remembers what matters.

## Positioning
Built by its owner as a cascaded pipeline (speech-to-text, language model, text-to-speech) with every stage measured. Latency per turn is a first-class, visible number, not a hidden metric.

## Operating Context
- Phase 1 (now): text chat only. The browser sends typed messages and receives the reply streamed token by token over a WebSocket.
- Phase 3 (later): the same page becomes the voice client: microphone capture, audio playback, live transcript, and a visible session state (idle, listening, thinking, speaking, interrupted). The layout must leave room for this without a redesign.
- Runs locally (Quarkus on localhost). Single static HTML file served by the backend, no build step.

## Capabilities and Constraints
- Replies stream in; per-turn latency (first token, total) is available and should be visible.
- Actions available now: send a message, start a new conversation (forget context), view the latency report.
- Replies are written to be spoken: short, no markdown or lists.
- Errors arrive as short spoken-style apologies, not error codes.
- Undecided: tools (Phase 5) and memory (Phase 6) UI.

## Brand Commitments
- Name: Wednesday. Interface copy in Spanish.

## Evidence on Hand
No logo, imagery or brand assets exist. Do not fabricate any.

## Product Principles
- Conversation first: the exchange is the interface; everything else stays out of the way.
- Show the machinery honestly: state and latency are visible because the owner is building and tuning them.
- Voice-ready: every text-era decision must survive the arrival of the microphone.
- Personal, not a platform: one user, no account chrome, no marketing.
