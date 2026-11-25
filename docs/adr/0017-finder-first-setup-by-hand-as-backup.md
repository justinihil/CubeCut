# ADR-0017: The finder comes first; the setup by hand is a backup and goes in 4.0

- **Status:** Accepted
- **Date:** 2026-11-25 (3.3-C)

## Context
CubeCut has two ways to find and read a timer, which share no reading code: the old one (four dots and a typed number, `TimerReader.kt` and `SolveAnalyzer.kt`) and the finder (3.3-C). On the two QiYi reference videos the finder found 85 of 85 solves with nothing false, where the old way found 83 with 19 false. The database can't tell a seven-segment setup by hand from a solid one. The owner's goal is to get rid of the old way.

## Decision
- **The finder goes first on every video**, also on those set up by hand before. Solid digits first; seven-segment only when no solid number runs.
- **A setup by hand is a backup:** used when the finder finds no timer, and instead of asking when two numbers run. It sits in a small part of the Setup box.
- **The old way is removed early in 4.0** (roadmap 4.0-0), once timers that hide their running time are read.
- **What the app can't read, the user adds** with *Add a missed solve*. Hints say where a solve may be missing (roadmap 3.3-C, step 4).

## Consequences
- A setup by hand can't overrule a finder that finds the timer but reads it wrong. The user then corrects the solves.
- With csTimer and a QiYi in view, csTimer is always used, whatever the dots said.
- Nothing re-scans on its own, and edited cuts stay ([ADR-0013](0013-rescan-merge-keeps-user-edits.md)).
- Until it is removed, the old way is the only one for timers without a running time, and possibly for Stackmat, GAN and LED timers (not tested).
- [ADR-0014](0014-display-reader-seam.md) (`DisplayReader`) only concerns the old way and goes with it.

## Alternatives considered
- **Switch only the seven-segment setups:** look for such digits inside the dots. More code for something that is going away.
- **New setups by hand always win:** needs a database field, for the same reason not worth it.
- **Remove the old way now:** nothing would read a timer that hides its running time.
