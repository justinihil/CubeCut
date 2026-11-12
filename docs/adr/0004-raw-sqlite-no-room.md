# ADR-0004: Raw SQLiteOpenHelper for persistence, no Room

- **Status:** Accepted (documented retroactively)
- **Date:** 3.0.0 Phase 1 (2026-11)

## Context
3.0.0 needed to remember every imported video, its cuts and its exports.

## Decision
- Plain Android SQLite via `SQLiteOpenHelper` in `SessionStore.kt`. The code comment states the reason: "no extra libraries, nothing to add to the Gradle files".
- Store **numbers and text only**. A thousand solves take a few dozen KB.

## Consequences
- Migrations are hand-written `ALTER TABLE … ADD COLUMN` steps in `onUpgrade`. Each schema change bumps `DB_VERSION`, and old steps are never edited.
- There is no `onDowngrade`, so an older APK can't open a newer database.
- There is no compile-time SQL checking, and the queries are strings.
- `saveSession` rewrites all cuts of a video, so cut ids are unstable and exports link by stop time ([data-model](../data-model.md#how-exports-link-to-cuts)).

## Alternatives considered
- Permanent cut ids (needed to link exports by id) were not chosen: they would have meant a database upgrade and much more code.
