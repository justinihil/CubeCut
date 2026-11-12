# ADR-0009: No Google backup or device transfer

- **Status:** Accepted (documented retroactively). Open to revisit ([roadmap](../roadmap.md) item 5).
- **Date:** 3.0.0 review fixes (2026-11-09)

## Context
The database stores `content://` uris and persisted grants that only mean something on the phone that created them. The README promises that nothing leaves the phone.

## Decision
`allowBackup="false"`, plus `data_extraction_rules.xml` excluding every domain from both `cloud-backup` and `device-transfer`. On Android 12+, `allowBackup` alone doesn't stop device transfer.

## Consequences
- A reinstall or a new phone starts with an empty Gallery. Exported files in `Movies/CubeCut` survive, because they are the user's files.
- Any backup feature must:
  - re-identify videos without their old uris (name+size+duration, see [ADR-0005](0005-video-identity-heuristics.md));
  - offer a way to reconnect many videos at once;
  - decide whether a user-initiated export file is compatible with the "nothing leaves the phone" promise.
