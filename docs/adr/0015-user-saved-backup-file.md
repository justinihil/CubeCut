# ADR-0015: A backup file the user saves, restored by merging and bulk reconnect

- **Status:** Accepted. Supersedes [ADR-0009](0009-no-backup-or-transfer.md).
- **Date:** 2026-11-23 (roadmap phase 3.2-G)

## Context
Since 3.0 the Gallery (solves, fixes, notes, exports and, from 3.2, tags, penalties, buffers and *Shared on*) lives only in CubeCut's database. Uninstalling or changing phones loses all of it ([ADR-0009](0009-no-backup-or-transfer.md)). The owner asked for a backup that survives a reinstall and moves the Gallery to a new phone.

The constraints are unchanged:
- the stored `content://` uris and persisted grants mean nothing after a reinstall or on another phone, and an old MediaStore uri can even point at a *different* video there;
- the app has no INTERNET permission and the README promises that nothing leaves the phone without the user's action ([ADR-0001](0001-offline-classic-cv-no-ml.md));
- Google backup and device transfer would copy the uris and grants blindly.

## Decision
- **One versioned JSON file** (`format` 1), written with `CreateDocument` wherever the user chooses. It holds every video's record, identified by file name, size and length (never its uri), every cut with all its fields, and every live export record (by file name). **No video.** Google backup and device transfer stay off (`allowBackup="false"`, `data_extraction_rules.xml`).
- **Restore merges, and the phone wins**: a video this phone already has (`matchVideo`) is left exactly as it is; every other video is added **without a link** (`uri = ''`), and so are its exports.
- **Reconnect in bulk** by picking many files with `OpenMultipleDocuments`: videos match by name + size + length (length within 1 s, since two phones may read it slightly differently), else size + length; exported clips match by exact file name. Importing a video again also reconnects it, through the usual identity rules ([ADR-0005](0005-video-identity-heuristics.md)).
- **No new permission.** Exported files are relinked by the user picking them, not with `READ_MEDIA_VIDEO`.

## Consequences
- The promise holds: the backup is a file the user makes and moves themselves; nothing is sent.
- A restored Gallery shows nothing playable until its videos are reconnected (sessions show as *Video deleted* on the Footage tab; single solves show *Video missing*). Restored exports are hidden until reconnected, so their solves show as *not exported* in the meantime.
- Relinked exported files are SAF document links. Trash and rename still work through `mediaStoreUriOf` when they were picked from the picker's Videos list; a rename asks Android first (`createWriteRequest`), because CubeCut no longer owns them.
- A new field must be added to `Backup.write` / `Backup.restore`. A change of meaning bumps `BACKUP_FORMAT`; a reader refuses a newer format.
- Restore never overwrites: to replace a video's data with the backup's, forget it first, then restore.

## Alternatives considered
- **`READ_MEDIA_VIDEO`** to find `Movies/CubeCut` files without picking: rejected by the owner. It means a permission prompt, Google Play limits it for apps that aren't galleries, and picking is enough.
- **Restore replaces the Gallery, or the backup wins**: rejected. Merging with the phone winning can't lose anything on the phone.
- **Google backup / device transfer**: rejected as in ADR-0009, because the uris and grants are copied blindly.
