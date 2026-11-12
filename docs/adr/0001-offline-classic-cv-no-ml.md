# ADR-0001: Offline, classic computer vision, no ML, no INTERNET permission

- **Status:** Accepted (documented retroactively)
- **Date:** from the start of the project (1.0.0)

## Context
CubeCut processes personal practice videos. The owner set hard requirements: run locally on the phone, use **no AI or machine learning**, and support 7-segment timers and screens other than the QiYi timer.

## Decision
- All detection uses classic OpenCV: ORB + RANSAC to find the timer, seven-segment classification of a thresholded picture, then counting and voting.
- No cloud services and no ML models.
- The app declares **no INTERNET permission**. Only the permissions listed in [android.md](../android.md#permissions) are present, including the two that Media3 adds.

## Consequences
- Privacy is simple: nothing leaves the phone, and the README says so.
- Only seven-segment-style fonts are readable. Supporting another font needs a new `DisplayReader` ([ADR-0014](0014-display-reader-seam.md)). The placeholder `GlyphReader` comments suggest a small TFLite classifier, which would break this rule. TODO(owner): decide whether a bundled, offline digit classifier would count as "ML" under this rule before building it ([roadmap](../roadmap.md) item 7).
- Hard cases are handled by the review UI (edit, add a missed solve, checks) rather than by endless tuning.

## Alternatives considered
- A colour-range timer finder (1.x) broke under different lighting. Shape matching (ORB) survived dark, bright, tinted, moved and rotated tests.
