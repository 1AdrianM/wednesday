# Audio fixtures

16 kHz, 16-bit signed little-endian, mono PCM WAV. Synthetic speech generated with espeak-ng
(`-s 150`), resampled with ffmpeg (`-ar 16000 -ac 1 -c:a pcm_s16le`).

| File | Language | Text |
|---|---|---|
| `es-que-hora-es.wav` | Spanish | Hola Wednesday, ¿qué hora es? |
| `en-what-time-is-it.wav` | English | Hello Wednesday, what time is it? |
