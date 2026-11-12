# ADR-0010: Portrait lock and configChanges instead of state restoration

- **Status:** Accepted (documented retroactively)
- **Date:** 3.0.0 review fixes (2026-11-09)

## Context
State lives in plain `remember` ([ADR-0003](0003-single-module-plain-compose-state.md)). Rotating the phone, switching dark mode or changing font size recreated the activity, which closed the editor and stopped a running Find solves or export.

## Decision
`screenOrientation="portrait"`, and a `configChanges` list covering orientation, screen size and layout, `uiMode`, density, font scale, keyboard, navigation, locale and layout direction. The activity redraws itself instead of being recreated.

## Consequences
- Dark-mode, font and split-screen changes keep all state.
- There is no landscape layout. Tablets and foldables may ignore the lock; the app then rotates without losing state, but the layout isn't designed for it.
- **Removing an entry from `configChanges` reintroduces state loss.** Process death still loses screen state.
