# Architecture Decision Records

One file per significant decision. ADRs 0001–0014 were **documented retroactively** (2026-11-12), using the old project guide's decision log, the git history and the code. They record only reasoning that was written down at the time. Anything unclear is marked `TODO(owner)`.

To add a new one, copy [0000-template.md](0000-template.md) to the next number. To reverse a decision, write a new ADR that supersedes the old one, and set the old one's status to `Superseded by ADR-NNNN`.

| # | Decision | Status |
|---|---|---|
| [0001](0001-offline-classic-cv-no-ml.md) | Offline, classic computer vision, no ML, no INTERNET permission | Accepted |
| [0002](0002-cut-at-first-frame-showing-final-time.md) | The cut is the first frame showing the final time; no sound analysis | Accepted |
| [0003](0003-single-module-plain-compose-state.md) | One module, flat package, plain Compose state; no ViewModel, Navigation or DI | Accepted |
| [0004](0004-raw-sqlite-no-room.md) | Raw `SQLiteOpenHelper` for persistence, no Room | Accepted |
| [0005](0005-video-identity-heuristics.md) | `OpenDocument` + persisted grants; recognise videos by uri, then name, size and duration | Accepted |
| [0006](0006-lossless-export-to-mediastore.md) | Lossless remux export to MediaStore `Movies/CubeCut`; store cuts, compute clips | Accepted |
| [0007](0007-mediacodec-frame-source-with-fallback.md) | One running MediaCodec decoder for the scan, with an automatic retriever fallback | Accepted |
| [0008](0008-never-delete-permanently.md) | Never delete permanently; use the MediaStore trash after a visual warning | Accepted |
| [0009](0009-no-backup-or-transfer.md) | No Google backup or device transfer | Accepted (open to revisit: roadmap item 5) |
| [0010](0010-portrait-lock-config-changes.md) | Portrait lock and `configChanges` instead of state restoration | Accepted |
| [0011](0011-foreground-service-for-notification-only.md) | Foreground service only for the notification; work stays in the screen | Accepted |
| [0012](0012-min-sdk-30-arm64-only.md) | Android 11+ only, arm64-only, R8 off | Accepted |
| [0013](0013-rescan-merge-keeps-user-edits.md) | Re-scans merge with saved cuts and keep user edits | Accepted |
| [0014](0014-display-reader-seam.md) | Digit reading behind a `DisplayReader` seam; seven-segment only for now | Accepted |
