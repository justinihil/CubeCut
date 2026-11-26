# ADR-0014: Digit reading behind a DisplayReader seam; seven-segment only for now

- **Status:** Retired in 4.0-0: `DisplayReaders.kt` went with the setup by hand ([ADR-0017](0017-finder-first-setup-by-hand-as-backup.md)). Solid digits had stopped going through it with [ADR-0016](0016-find-the-timer-without-ml.md)
- **Date:** 1.1.0

## Context
csTimer screens and other 7-segment displays needed support, and "any font" was wanted eventually. Only the step that turns a straightened, binarised picture into digits depends on the font.

## Decision
- The `DisplayReader` interface (`learn`, `read`, `learnUnverified`) lives in `DisplayReaders.kt`, and `DisplayReaders.all` is ordered cheapest first.
- Finding, straightening and binarising the display are shared. So are the vote and the number formatting.
- `SegmentReader` is the only working reader. `GlyphReader` is a disabled placeholder.
- The winning reader's id is stored in `DigitLayout.readerId`.

## Consequences
- A new font is added in one file, without touching the scan or the UI.
- Readers must return `null` when unsure, because the vote depends on it.
- The current setup flow still needs the user to mark the display and type the number shown. Removing that ([roadmap](../roadmap.md) phase 3.3-C) is a bigger change than adding a reader.

## Alternatives considered
- Approach 2 (any font, probably a small TFLite classifier) was deferred. See [ADR-0001](0001-offline-classic-cv-no-ml.md) for the open question about whether that counts as ML.
