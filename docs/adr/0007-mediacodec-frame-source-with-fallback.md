# ADR-0007: One running MediaCodec decoder for the scan, with an automatic retriever fallback

- **Status:** Accepted (documented retroactively)
- **Date:** 2.0.0 speed-up (2026-11-02)

## Context
Measurements showed that the first full-video scan took 107 s, of which 92 s was spent getting frames: one `MediaMetadataRetriever` seek per read, about 250 ms each. The code could only be tested on one phone.

## Decision
- `CodecSource` keeps one `MediaCodec` decoder running and reads the Y plane directly.
- `AutoSource.calibrate` compares it with `RetrieverSource` at startup, which also settles the rotation.
- On any mismatch, exception or stall, the scan **permanently falls back** to the retriever.

## Consequences
- The scan went from 107 s to 26.8 s on the test session.
- The fallback is the only protection on untested phones and decoders (HDR, 10-bit), so **it must be kept**.
- A decoder that returns wrong frames after a seek without failing would not be caught, because calibration checks only one picture.
- ExoPlayer, the codec and the calibration retriever may each hold a hardware decoder at the same time.

## Alternatives considered
- **A parallel scan in 2–3 pieces:** built and measured at 105 s vs 107 s, because each frame got 1.8× slower and the decoder is the limit. Removed.
- **A cropped ORB search near the last position:** declined by the owner.
- **"Last position first" in the engine:** kept, although it saves little.
