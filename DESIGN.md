# Design

Visual system of the Wednesday browser client ([index.html](src/main/resources/META-INF/resources/index.html)). Product facts live in [PRODUCT.md](PRODUCT.md); the chat surface's direction contract lives in [.impeccable/surfaces/chat.md](.impeccable/surfaces/chat.md).

## World

Live television subtitling. The conversation reads like a subtitled broadcast: speakers are told apart by caption colour, replies arrive inside a black caption plate, and a red "en antena" tally shows when Wednesday is working. Dark because of the scene: the owner uses it at a desktop, often in the evening, next to a dark IDE.

## Colour

Strategy: restrained ground, committed speaker colours. Speaker colours follow the Spanish subtitling convention (UNE 153010).

| Token | Value | Job |
|---|---|---|
| `--ground` | `#10141b` | Page ground (studio charcoal, blue tint) |
| `--ground-raised` | `#161b24` | Top bar, latency rail, raised controls |
| `--ground-sunken` | `#0b0e13` | Composer field, bar tracks |
| `--plate` | `#05070a` | Caption plate behind live text |
| `--line` / `--line-strong` | `#262e3b` / `#364052` | Dividers / control borders |
| `--text` / `--text-dim` / `--text-faint` | `#eef1f6` / `#a3aec0` / `#7d899c` | Primary / secondary / tertiary text |
| `--wednesday` | `#ffd84a` | Wednesday's words, primary button, latency target marker |
| `--owner` | `#6fd6ff` | The owner's words, focus ring, caret |
| `--system` | `#7ee2a8` | System notices |
| `--alert` | `#ff7ad9` | Errors, "sin señal" |
| `--tally` | `#e8333a` | "Pensando" on-air tally (later: listening/speaking) |
| `--ok` / `--slow` / `--late` | `#7ee2a8` / `#ffb547` / `#ff6b6b` | Latency against target: ≤ target, ≤ 2,5 s, above |

## Type

- **Atkinson Hyperlegible Next** (variable, self-hosted in `fonts/`) for all text. Chosen for legibility, which is what subtitles exist for.
- **Atkinson Hyperlegible Mono** only for measurements: timecodes, latency figures, table numerals (`tabular-nums`).
- Scale: transcript 18/1.55, UI 14–16, labels 13 (uppercase, +0.06–0.08em), rail figures 20 mono. Transcript measure capped at 68ch.

## Signature

The live caption plate. While a reply streams, its lines sit on `--plate` with `box-decoration-break: clone` (one plate per line, like broadcast captions) and a blinking block cursor. When the reply lands the plate dissolves (700 ms, expo ease-out) and the gutter shows `+first-token` and `total`, coloured against the target. This is the one authored motion; everything else is ≤160 ms state change. Reduced motion disables it all.

## Components

- **Top bar:** name, model/context meta, tally (`role=status`), "Latencias" toggle (`aria-pressed`), "Nueva conversación".
- **Transcript line:** two columns, right-aligned gutter (speaker + timecode, 112px) and body. Collapses to one column under 720px.
- **Composer:** sunken field, cyan text, focus border in `--owner`; yellow "Enviar". Enter sends, Shift+Enter breaks a line.
- **Latency rail:** target, p50/p95 of first token, per-turn table with a bar per turn. Bar length is exact duration on a shared axis: total in `--line-strong`, first token in its grade colour, target as a yellow tick. Persistent at ≥1100px, a drawer below.
- **States:** connecting, idle, thinking, offline (auto-reconnect with backoff; a reconnection says plainly that it is a new session), empty transcript with three suggestions, server errors as magenta "Aviso" lines.

## Phase 3 reach

The tally gains `escuchando`, `hablando` and `interrumpido`; the composer gains a microphone control at its leading edge; the live user transcript uses the same caption plate in `--owner`.
