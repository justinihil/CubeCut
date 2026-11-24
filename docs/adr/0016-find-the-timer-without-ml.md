# ADR-0016: Find and read solid-digit timers without ML, from example digits of open fonts

- **Status:** Accepted
- **Date:** 2026-11-24 (3.3-C)

## Context
3.3 should find the timer by itself, whatever its font, angle or place. [ADR-0001](0001-offline-classic-cv-no-ml.md) rules out ML unless the owner decides otherwise, and [ADR-0014](0014-display-reader-seam.md) expected "any font" to need a small model. A PC prototype without ML found 25 of 25 solves in each of three csTimer videos, one of them untouched until the method was final ([roadmap 3.3-C](../roadmap.md)).

## Decision
- **No ML.** Solid digits are read by comparing joined blobs with example digits (`SolidDigits.kt`).
- **The examples ship with the app** (`assets/digit_shapes.bin`, 72 KB): 11 families, all under the SIL Open Font License. Chosen by the owner over the phone's own fonts, so that what is measured on the PC holds on every phone.
- **Pure Kotlin, no OpenCV and no Android** in the reader, so unit tests and the reference videos on the PC run exactly the code the phone runs.
- **Digit examples of the video itself replace the fonts** once all ten digits were seen, named by the clock.
- **Unsure which number is the timer:** ask, with a picture of it. Otherwise don't ask (owner).
- **A solve the clock doesn't confirm** is kept and flagged (owner).
- **Good enough** means reliable in a reasonable setting, with the timer reasonably visible for most of the footage (owner).

## Consequences
- The reader works on a whole picture, before any display is located. It therefore doesn't fit `DisplayReader`, which starts from a straightened display. `GlyphReader` (the placeholder for a model) was removed when *Find solves* started to use the finder.
- Seven-segment digits are not read this way (their strokes are separate pieces). They keep `SegmentReader`.
- The licence text must stay in the app (`assets/digit_fonts_license.txt`, the About window) and in `NOTICE.md`.
- Known limits: digits under about 24 px, strong blur, and screens turned far over.

## Alternatives considered
- **The phone's own fonts:** no licence text and no asset, but they differ per phone and can be changed by the user.
- **A small model:** not needed on the reference videos.
