# ADR-0006: Lossless remux export to MediaStore; store cuts, compute clips

- **Status:** Accepted (documented retroactively)
- **Date:** 1.0.0 (export); 3.0.0 (store cuts, compute clips)

## Context
Sessions are long (about 1.5 GB, around 22 solves), and exporting has to be fast and keep full quality. The Gallery, the editor and export must always agree on where a clip starts and ends.

## Decision
- Export with `MediaExtractor` + `MediaMuxer`, with no re-encoding, into MediaStore `Movies/CubeCut` (`IS_PENDING` while writing).
- Persist only cuts. Clips are always derived with `computeClips(cuts, beforePad, afterPad, duration)`.

## Consequences
- Export is fast and lossless.
- A clip starts on a keyframe, so it can begin up to about 1 s early. This is most visible on solve-only clips.
- An exported file is a snapshot. Later edits don't change it; the user exports again.
- Export runs without a foreground service, so leaving the app during a long batch could interrupt it.
- A frame-exact option would need re-encoding (for example Media3 Transformer). Not built.
