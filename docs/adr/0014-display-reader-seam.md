# ADR-0014: Digit reading behind a DisplayReader seam; seven-segment only for now

- **Status:** Accepted (documented retroactively)
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
- The current setup flow still needs the user to mark the display and type the number shown. Removing that ([roadmap](../roadmap.md) item 7) is a bigger change than adding a reader.

## Alternatives considered
- Approach 2 (any font, probably a small TFLite classifier) was deferred. See [ADR-0001](0001-offline-classic-cv-no-ml.md) for the open question about whether that counts as ML.
