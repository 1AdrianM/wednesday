# Wednesday

**Realtime voice assistant with AI-powered conversation**

A personal voice assistant built as a cascaded pipeline (Speech-to-Text → Language Model → Text-to-Speech) with **latency-first design**. Every stage is measured, visible, and optimized for fast conversational interaction.

> **Current Phase**: 3 (Voice client with push-to-talk)  
> **Status**: Pre-release (voice working end-to-end, latency optimization pending)

---

## 🎯 Features

### ✅ Implemented (Phase 0-3)

- **🎤 Realtime Speech-to-Text** (Gemini Transcribe Live)
  - 16 kHz 16-bit mono audio (20 ms frames)
  - Partial + final transcripts
  - ~0.4s latency after audio ends

- **🧠 Conversational AI** (Gemini Flash)
  - Context-aware (last N turns)
  - Spanish ↔ English language switching
  - Spoken-style responses (no markdown, lists, or closing questions)
  - p50 first token: ~0.65s | p95: ~4.2s

- **🔊 Natural Text-to-Speech** (Gemini TTS)
  - Streaming synthesis (24 kHz PCM)
  - Sentence-based chunking (audio starts before LLM finishes)
  - First audio: ~1.2s after request

- **🌐 Browser Voice Client**
  - Push-to-talk with AudioWorklet (resampling to 16 kHz)
  - Live transcript display
  - Session state visualization (idle, listening, thinking, speaking)
  - WebSocket streaming (binary audio + JSON events)

- **📊 Latency Tracking**
  - Per-turn breakdown (first token, first audio, total)
  - P50/P95 metrics
  - Visual latency report
  - Target: 1.2s first audio (currently ~2-3s)

- **✅ LLM-as-a-Judge Evaluation**
  - Automated quality assessment against system prompt rules
  - 9 evaluation criteria (brevity, no markdown, spoken numbers, etc.)
  - 12 predefined test cases with good/bad response pairs
  - Detailed scoring (0.0-1.0) with reasoning per criterion
  - CSV export for trend analysis
  - See `docs/EVALUATION.md`

### 🚧 Planned (Phase 4+)

- **Phase 4**: Barge-in (interrupt while speaking)
- **Phase 5**: Tool use (calendar, weather, reminders)
- **Phase 6**: Long-term memory (RAG)
- **Phase 7**: Latency optimization (p95 < 2.5s)

---

## 🏗️ Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                      Browser Client                          │
│  ┌────────────┐  ┌──────────────┐  ┌──────────────────────┐ │
│  │ Microphone │→ │ AudioWorklet │→ │ WebSocket (binary)   │ │
│  │            │  │ (16kHz PCM)  │  │ /ws/voice            │ │
│  └────────────┘  └──────────────┘  └──────────────────────┘ │
│         ↑                                      ↓              │
│  ┌────────────┐  ┌──────────────┐  ┌──────────────────────┐ │
│  │ Audio Play │← │ PCM Decoder  │← │ Binary audio stream  │ │
│  └────────────┘  └──────────────┘  └──────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
                            │
                            ↓
┌──────────────────────────────────────────────────────────────┐
│                   Quarkus Backend                            │
│                                                              │
│  ┌────────────────────────────────────────────────────────┐ │
│  │               VoiceSession (Actor)                     │ │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────────────┐ │ │
│  │  │  State   │→ │  Audio   │→ │  Event Queue         │ │ │
│  │  │  Machine │  │  Buffer  │  │  (virtual thread)    │ │ │
│  │  └──────────┘  └──────────┘  └──────────────────────┘ │ │
│  └────────────────────────────────────────────────────────┘ │
│                            ↓                                 │
│  ┌────────────────────────────────────────────────────────┐ │
│  │             Cascaded Pipeline                          │ │
│  │                                                        │ │
│  │  STT          LLM           TTS                        │ │
│  │  (Gemini)  →  (Gemini)   →  (Gemini)                  │ │
│  │  Transcribe   Flash         TTS                        │ │
│  │  Live         Lite          API                        │ │
│  │                                                        │ │
│  │  Partial →    Token  →      Audio                     │ │
│  │  Final        Stream        Chunk                     │ │
│  └────────────────────────────────────────────────────────┘ │
│                            ↓                                 │
│  ┌────────────────────────────────────────────────────────┐ │
│  │               Timeline + Metrics                       │ │
│  │  - First token timestamp                              │ │
│  │  - First audio timestamp                              │ │
│  │  - P50/P95 latencies                                  │ │
│  └────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

**Component Organization** (Hexagonal Architecture):
```
com.github.wednesday/
├── gateway/        # WebSocket handlers (thin adapter layer)
├── session/        # VoiceSession actor + state machine
├── speech/         # STT interface + providers
├── ai/            # LLM interface + providers
├── tts/           # TTS interface + providers
├── context/       # Conversation context + system prompt
└── common/        # Shared models (ChatMessage, ModelRequest)
```

**Provider Strategy**:
- Adapters live in `<component>.provider.<name>`
- Fakes for testing in `<component>.fake`
- ArchUnit enforces boundaries

---

## 🚀 Quick Start

### Prerequisites

- Java 21+
- Maven 3.9+
- Gemini API key ([AI Studio](https://aistudio.google.com/))

### Setup

```bash
# 1. Clone
git clone https://github.com/1AdrianM/wednesday.git
cd wednesday

# 2. Configure API key
export GEMINI_API_KEY='your_api_key_here'

# 3. Run with fake components (no API calls)
export WEDNESDAY_LLM=fake
export WEDNESDAY_STT=fake
export WEDNESDAY_TTS=fake
./mvnw quarkus:dev

# 4. Run with real Gemini (uses API quota)
unset WEDNESDAY_LLM WEDNESDAY_STT WEDNESDAY_TTS
./mvnw quarkus:dev

# 5. Open browser
open http://localhost:8080
```

**Browser client:**
- Press and hold "Hablar" button to speak
- Release to process
- Watch the state indicator (idle → listening → thinking → speaking)

### Evaluation System

```bash
# Setup
export GEMINI_API_KEY='your_key'

# Quick test
./eval single

# Full suite (12 test cases × 2 = 24 evaluations)
./eval all

# See docs/EVALUATION.md for details
```

---

## 📊 Performance Targets

| Stage | Current | Target | Phase |
|-------|---------|--------|-------|
| **STT** (final) | ~0.4s | 0.3s | ✓ |
| **LLM** (first token p50) | ~0.65s | 0.5s | 7 |
| **LLM** (first token p95) | ~4.2s | 1.5s | 7 |
| **TTS** (first audio) | ~1.2s | 0.8s | 7 |
| **End-to-end** (first audio) | ~2-3s | **1.2s** | 7 |

**Measurement methodology:**
- Timestamps from earliest mark per turn (SPEECH_END for voice)
- Averaged over 20+ turns for statistical significance
- P50/P95 for handling outliers

---

## 🧪 Testing

```bash
# Unit + integration tests
./mvnw verify

# Architecture tests (component boundaries)
./mvnw test -Dtest=ArchitectureTest

# Specific component
./mvnw test -Dtest=VoiceSessionStateMachineTest

# Sample turn with latency report
./mvnw test -Dtest=SampleTurnTest

# LLM-as-a-Judge evaluation
export GEMINI_API_KEY='your_key'
./eval all
```

---

## 📁 Project Structure

```
wednesday/
├── src/main/java/com/github/wednesday/
│   ├── gateway/              # WebSocket endpoints
│   ├── session/              # VoiceSession actor
│   ├── speech/               # STT (Gemini Transcribe Live)
│   ├── ai/                   # LLM (Gemini Flash Lite)
│   ├── tts/                  # TTS (Gemini TTS)
│   ├── context/              # Conversation context
│   └── common/               # Shared models
│
├── src/main/resources/
│   ├── prompts/system.txt    # System prompt (editable)
│   └── META-INF/resources/
│       └── index.html        # Browser client (single file)
│
├── src/test/java/com/github/wednesday/
│   ├── evaluation/           # LLM-as-a-Judge system
│   │   ├── EvaluationCriteria.java
│   │   ├── GeminiJudge.java
│   │   ├── TestCases.java
│   │   └── WednesdayEvaluationSuite.java
│   └── ...                   # Component tests
│
├── docs/
│   ├── PRODUCT.md            # Product vision
│   ├── DESIGN.md             # UI/UX design system
│   ├── PROGRESS.md           # Phase tracking
│   ├── IDEAS.md              # Future features
│   ├── EVALUATION.md         # LLM-as-a-Judge docs
│   ├── QUICKSTART-EVALUATION.md
│   └── adr/                  # Architecture Decision Records
│       ├── 0001-architecture-and-providers.md
│       ├── 0002-audio-format.md
│       ├── 0003-voice-client.md
│       └── 0004-llm-as-judge-evaluation.md
│
├── eval                      # Evaluation runner script
├── chat                      # CLI chat (deprecated, use browser)
├── pom.xml                   # Maven config
└── README.md                 # This file
```

---

## 🎨 Design

**Visual language**: Live television subtitling

- Dark studio aesthetic (owner uses it at desktop, often in evening)
- Spanish subtitling colors (UNE 153010):
  - 🟡 Yellow: Wednesday's words
  - 🔵 Cyan: Owner's words
  - 🟢 Green: System notices
  - 🔴 Red: "En antena" tally (thinking/speaking/listening)
- **Atkinson Hyperlegible** font (designed for maximum legibility)
- Live caption plate with `box-decoration-break: clone`
- Latency visible per turn (first token, total)

See `DESIGN.md` for complete design system.

---

## 🧰 Tech Stack

**Backend:**
- [Quarkus](https://quarkus.io/) 3.17.8 (reactive Java)
- Java 21 (virtual threads for session actors)
- Jackson (JSON)
- Java HttpClient (Gemini API)

**AI Providers:**
- [Gemini](https://ai.google.dev/) 3.5 Flash Lite (LLM)
- Gemini Transcribe Live (STT)
- Gemini TTS (voice synthesis)

**Frontend:**
- Vanilla JavaScript (zero frameworks)
- WebSocket (binary audio + JSON events)
- AudioWorklet (audio processing)
- CSS Grid + Flexbox

**Testing:**
- JUnit 5
- ArchUnit (architecture tests)
- LLM-as-a-Judge (quality evaluation)

---

## 📚 Documentation

| Document | Purpose |
|----------|---------|
| [PRODUCT.md](PRODUCT.md) | Product vision and operating context |
| [DESIGN.md](DESIGN.md) | UI/UX design system (colors, type, motion) |
| [PROGRESS.md](PROGRESS.md) | Phase tracking and acceptance criteria |
| [IDEAS.md](IDEAS.md) | Future features and explorations |
| [EVALUATION.md](docs/EVALUATION.md) | LLM-as-a-Judge evaluation system |
| [ADR 0001](docs/adr/0001-architecture-and-providers.md) | Architecture decisions |
| [ADR 0002](docs/adr/0002-audio-format.md) | Audio format (16 kHz, 20 ms frames) |
| [ADR 0003](docs/adr/0003-voice-client.md) | Voice client design |
| [ADR 0004](docs/adr/0004-llm-as-judge-evaluation.md) | Evaluation system |

---

## 🗺️ Roadmap

### ✅ Phase 0-3: Foundation + Voice (Completed)
- [x] Component architecture
- [x] Fake providers for testing
- [x] Gemini LLM integration (streaming)
- [x] Gemini TTS integration (streaming)
- [x] CLI chat (deprecated)
- [x] Browser text client (deprecated)
- [x] Gemini STT integration (Transcribe Live)
- [x] Voice WebSocket with binary audio
- [x] Browser voice client (push-to-talk)
- [x] Session state machine
- [x] LLM-as-a-Judge evaluation system

### 🚧 Phase 4: Barge-in (Next)
- [ ] Interrupt detection while speaking
- [ ] Clean cancellation of in-flight TTS
- [ ] Resume conversation after interruption

### 📋 Phase 5: Tool Use
- [ ] Tool definition framework
- [ ] Natural narration ("Checking the weather...")
- [ ] Progress updates on long tools
- [ ] Layered fallbacks

### 📋 Phase 6: Memory
- [ ] Long-term context storage
- [ ] Retrieval (RAG)
- [ ] Semantic search

### 📋 Phase 7: Latency Optimization
- [ ] Pre-open STT connection (avoid 0.3-7s connect time)
- [ ] Shorter first TTS segment (cut at comma)
- [ ] Speculative LLM start on partial transcript
- [ ] Target: p95 first audio < 2.5s

---

## 🤝 Contributing

This is a personal learning project. Issues and suggestions welcome, but PRs may not be accepted as the owner is building this hands-on.

**Useful contributions:**
- Bug reports with reproduction steps
- Latency measurement results on your hardware
- Alternative provider suggestions (ElevenLabs, Deepgram, etc.)
- UI/UX feedback

---

## 📄 License

MIT License - see [LICENSE](LICENSE) file

---

## 🙏 Acknowledgments

- Inspired by Jarvis (Iron Man) and Her (movie)
- Design influenced by live TV subtitling (TVE, BBC)
- Built with guidance from Claude (Anthropic)

---

## 📞 Contact

**GitHub**: [@1AdrianM](https://github.com/1AdrianM)  
**LinkedIn**: [Adrian Mateo](https://www.linkedin.com/in/adrian-mateo-425a03257/)

---

**Status**: Active development | Phase 3 complete | Pre-release  
**Last updated**: November 2026
