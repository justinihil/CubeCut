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
| [0009](0009-no-backup-or-transfer.md) | No Google backup or device transfer | Superseded by 0015 |
| [0010](0010-portrait-lock-config-changes.md) | Portrait lock and `configChanges` instead of state restoration | Accepted |
| [0011](0011-foreground-service-for-notification-only.md) | Foreground service only for the notification; work stays in the screen | Superseded by 0018 |
| [0012](0012-min-sdk-30-arm64-only.md) | Android 11+ only, arm64-only, R8 off | Accepted |
| [0013](0013-rescan-merge-keeps-user-edits.md) | Re-scans merge with saved cuts and keep user edits | Accepted |
| [0014](0014-display-reader-seam.md) | Digit reading behind a `DisplayReader` seam; seven-segment only for now | Retired in 4.0-0 |
| [0015](0015-user-saved-backup-file.md) | A backup file the user saves (no videos); restore merges, the phone wins; bulk reconnect by picking files | Accepted |
| [0016](0016-find-the-timer-without-ml.md) | Find and read solid-digit timers without ML, from example digits of open fonts | Accepted |
| [0017](0017-finder-first-setup-by-hand-as-backup.md) | The finder comes first; the setup by hand is a backup and goes in 4.0 | Accepted; done in 4.0-0 |
| [0018](0018-app-wide-analysis-queue.md) | Find solves runs in an app-wide queue, kept alive by the foreground service; the lock is per video | Accepted |
| [0019](0019-keep-the-processor-awake-while-scanning.md) | The queue keeps the processor awake while it runs (partial wake lock); the report says how long the phone slept | Accepted |
