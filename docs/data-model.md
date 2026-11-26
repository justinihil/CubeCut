# Data model

Everything CubeCut remembers, how a video is recognised, and how exported files link back to solves. The **source of truth** is the code: `SessionStore.kt` (`Helper.onCreate` and `onUpgrade`) for the schema, and `CutItem` (`ClipExporter.kt`) for the in-memory cut. Why it is raw SQLite: [ADR-0004](adr/0004-raw-sqlite-no-room.md).

## Where state lives

| What | Where | Survives |
|---|---|---|
| Videos, cuts, export records, where the timer was found per video | SQLite `cubecut_sessions.db` (`SessionStore`) | Restarts. **Not** uninstall, backup or device transfer ([ADR-0009](adr/0009-no-backup-or-transfer.md)) |
| App UI settings: grid/list, columns, sort keys, filters, hidden footage, theme mode, skip seconds, auto-replay, swipe hint | SharedPreferences `cubecut_ui` (`GalleryUiState`) | Restarts |
| Thumbnails and viewer stills | `cacheDir/thumbs` (`ThumbCache`); capped at 100 MB, trimmed to 80 MB | Until Android clears the cache; rebuilt on demand |
| Editor working state: selection, zoom, dialogs | `remember` in `EditorScreen` | Nothing. A process death returns to the Gallery, and cuts survive only through the 400 ms autosave |
| Exported clips | MediaStore `Movies/CubeCut` (user files) | Uninstall, because they are the user's files |
| Persisted URI read grants for imported videos | Android (`takePersistableUriPermission` in `SessionStore.keepAccess`) | Restarts, not reinstall. Capped at 512 grants; *Forget* releases one |
| Legacy 1.x files `timer_setup.json` / `timer_reference.png` | Deleted at start-up by `TimerStore.clear` until 4.0-0; since then left alone (harmless, nothing reads them) | — |

## Schema (DB version 11)

```mermaid
erDiagram
  footage ||--o{ cuts : "footage_id (ON DELETE CASCADE)"
  footage ||--o{ exports : "footage_id (ON DELETE CASCADE)"
  footage ||--o{ hints : "footage_id (ON DELETE CASCADE)"
  footage {
    int id PK
    text uri "persisted SAF content:// link"
    text display_name
    int size_bytes
    int duration_ms
    text title "editable; prefix of exported file names"
    int date_taken_ms "0 = unknown"
    int added_ms
    int last_scan_ms "0 = never"
    real before_pad "default 2.5"
    real after_pad "default 2.5"
    int scramble_all "default 1"
    int timer_frame_ms "v3; -1 = found, or none (before 4.0: set up by hand)"
    text timer_corners "v3; 8 doubles, comma separated: the timer box"
    text timer_typed "v3; not read since 4.0-0"
    int trashed_ms "v4; 0 = not in trash"
    text puzzle "v7; '' = not tagged"
    text category "v7; '' = not tagged"
    text kind "v7; session or single (3.2-F)"
    real trim_start_sec "v7; NULL = untrimmed (3.2-F)"
    real trim_end_sec "v7; NULL = untrimmed (3.2-F)"
    text scan_state "v9; queued, no_timer, asks or '' (4.0-C)"
    text cube "v11; '' = not tagged (4.0-F)"
    text place "v11; Home, Competition, Other or '' (4.0-F)"
  }
  cuts {
    int id PK "NOT stable: regenerated on every save"
    int footage_id FK
    real stop_sec "the cut = first frame showing the time"
    text time_text "as shown, e.g. 11.263 or 1:02.34"
    int included "0 only for left-over cuts"
    text note "scan/check note"
    int include_scramble
    text user_note "v2; free text + social-media words"
    int user_edited "v5; protects the cut from re-scan"
    int reviewed "v6; It's fine"
    text puzzle "v7; '' = follows the video"
    text category "v7; '' = follows the video"
    int plus2_count "v7; default 0"
    int dnf "v7; default 0"
    real before_pad "v7; NULL = follows the video"
    real after_pad "v7; NULL = follows the video"
    text shared_on "v7; e.g. TikTok,YouTube"
    int seen_ms "v10; 0 = New (4.0-D)"
    text cube "v11; '' = follows the video (4.0-F)"
  }
  exports {
    int id PK
    int footage_id FK
    real stop_sec "links to the cut, within SAME_SOLVE_SEC"
    text time_text
    text uri "MediaStore uri of the file"
    text file_name "the name Android actually gave"
    real offset_sec "where the stop is inside the file"
    int exported_ms
    int trashed_ms "v4; 0 = not in trash"
  }
  hints {
    int id PK "v8"
    int footage_id FK
    real from_sec "where a solve may be missing"
    real to_sec
    real seek_sec "where Go there puts the playhead"
    text reason "HintReason.key: ran, reset, unreadable"
    int dismissed "1 = the user said Nothing here"
  }
```

Migrations (`onUpgrade`) only add, one `if (oldVersion < N)` block per version:

| Version | Added |
|---|---|
| v2 | `user_note`, `exports` table |
| v3 | timer columns |
| v4 | `trashed_ms` ×2 |
| v5 | `user_edited`, plus a one-off guess at which old cuts were edited |
| v6 | `reviewed` |
| v7 | `footage`: `puzzle`, `category`, `kind`, `trim_start_sec`, `trim_end_sec`. `cuts`: `puzzle`, `category`, `plus2_count`, `dnf`, `before_pad`, `after_pad`, `shared_on`, plus a one-off fill of `shared_on` from the platform names in `user_note` (the note is left as it is) |
| v8 | `hints` table |
| v9 | `footage.scan_state` (4.0-C) |
| v10 | `cuts.seen_ms` (4.0-D), set to the upgrade time for every cut already saved |
| v11 | `footage.cube`, `footage.place`, `cuts.cube` (4.0-F) |

### v7 fields (3.2)

Added in 3.2-A with no UI; the later 3.2 phases build the screens. Every default means "nothing changed", so no clip moves on upgrade.

| Field | Meaning | Set by |
|---|---|---|
| `footage.puzzle`, `footage.category` | The video's tags. `''` = not tagged | `updateFootageTags` (a video setting, so no `user_edited`) |
| `cuts.puzzle`, `cuts.category` | The clip's own tags. `''` = follows the video; `'-'` (`NO_TAG`) = not tagged even if the video is (the editor's *not this puzzle* untick, 3.2-B). A typed *Other* name is stored as typed (`cleanTag`). A clip tag that comes out the same as its video's is written as `''` (`normalizeClipTag`, from 3.3), so it follows the video; rows written before that may still hold the video's name and show the same. `resolveTag` / `GalleryClip.puzzle` / `.category` resolve this | `updateCutTags`; the editor's untick goes through `saveSession` with `edited` set |
| `cuts.plus2_count`, `cuts.dnf` | The penalty (3.2-C). `time_text` keeps what the timer showed, so the solve-only start and the export link don't move. Only what is shown (`shownTime`, `clipTitle`), the sort and *best* (`GalleryClip.seconds` = `penalisedSeconds`: +2 s each, DNF = none) and the file name (`fileTimePart`) use the penalty. DNF and +2 exclude each other in the UI | `updateCutPenalty` (viewer sheet); the Edit window through `saveSession` with `edited` set |
| `cuts.before_pad`, `cuts.after_pad` | The clip's own buffers in seconds (3.2-D: *Follow video* / *Custom*). NULL = follows the video's slider. `computeClips` uses them for that clip only; its `after_pad` also moves where the next scramble starts | `updateCutPads` (viewer sheet); the Edit window through `saveSession` with `edited` set |
| `cuts.shared_on` | Where the clip was shared: names from `SOCIAL_PLATFORMS`, comma separated (`encodeSharedOn` / `decodeSharedOn`) | `updateCutSharedOn` (the viewer's *Shared on* chips, 3.2-E). The note is separate text; the one-off v7 fill was the only time it was read from the note |
| `footage.kind` | `session` (`KIND_SESSION`) or `single` (`KIND_SINGLE`, a clip of one solve, 3.2-F). A single solve has exactly one cut and never opens in the editor. Since 4.0-C no new ones are made (every video is scanned) and the old ones are listed on the Sessions tab too | `createSingle` (removed in 4.0-C) |
| `footage.trim_start_sec`, `trim_end_sec` | A single solve's trim in seconds of the file. NULL = untrimmed: the whole file is the clip and needs no export. `singleRange` / `clipsFor` turn it into the clip; the cut's `stop_sec` is the range's end | `createSingle`, `updateSingle` (moves the cut and its export record to the new end in one transaction) |

All the cut fields go through `saveSession`'s insert and `loadCuts`. `mergeRescan` keeps an edited cut as it is, and when it replaces an untouched cut with the new scan it still carries over every field only the user sets (`withUserFieldsOf`).

There is no `onDowngrade`, so installing an older APK over a newer database crashes on open.

## Video identity

`openOrCreate` decides whether an imported video is one CubeCut has seen before. It tries these in order:
1. same `uri`, and duration within 1000 ms;
2. same `display_name` + `size_bytes` + `duration_ms`;
3. same `size_bytes` + `duration_ms` (skipped if the size is unknown), so a renamed file is still recognised.

On a match it refreshes the stored uri, name and size, and returns `isNew = false`. `findKnown` applies the same rules without changing anything. *Import video* calls it first. When one video is picked, a known one shows *Imported before*; when several are, known ones are skipped (4.0-C). A known video shows *Imported before* (*Open saved* / *Cancel*) before `openOrCreate` runs, so Cancel leaves no trace. Opening a session from the Gallery doesn't ask. `relink` (*Reconnect video*) attaches a new uri to a saved session and only checks duration within 1500 ms. There is no content hash. Rationale: [ADR-0005](adr/0005-video-identity-heuristics.md).

## The timer columns

`timer_corners` is where CubeCut **found** the timer (3.3-C): the box around its numbers, only for the zoom in the Edit window (`timerPlaceOf`). `saveFoundTimer` writes it after every scan that found a timer, with `timer_frame_ms` −1 and an empty `timer_typed`. What the finder learned isn't stored; every scan learns it again.

Before 4.0 the same columns could hold a timer set up **by hand** (a picture time, the four corners and the typed number; [ADR-0017](adr/0017-finder-first-setup-by-hand-as-backup.md)). 4.0-0 removed that setup: such a row is never read as one any more, its corners still serve the zoom until the next scan replaces them, and the columns stay so old rows and backups load unchanged.

## Cube and place (v11)

`footage.cube` and `cuts.cube` work exactly like the puzzle (`''` on a clip = follows the video, `NO_TAG` = not tagged, a typed name as typed, `resolveTag`); the names already used are the list to pick from. `footage.place` is one of `PLACES` or `''`, on the video only. `updateFootageCubePlace` is a video setting (no `user_edited`); `updateCutCube` sets `user_edited`, and `withUserFieldsOf` carries `cube` over a re-scan. The cube is not part of exported file names. The backup writes all three; older backups restore them empty.

## New clips (v10)

`cuts.seen_ms` is when the clip was first opened in the viewer or exported; 0 = not yet. A clip is **New** when `seen_ms` is 0 and the cut isn't `user_edited` (`CutItem.isNew`: what you changed or added you have seen), and it has a solve and no live export (`isNewClip` in `Gallery.kt`). The upgrade to v10 sets every saved cut to the upgrade time, so nothing old shows as new.

- `markCutSeen` (the viewer shows the clip) finds the cut by `stop_sec` within 0.0005 s and does **not** set `user_edited`: looking at a clip doesn't change it, so a re-scan may still update it. `mergeRescan` carries `seen_ms` over (`withUserFieldsOf`), so a re-scan never makes a seen clip new again; a solve it adds is new.
- `recordExports` sets it for the exported solves (within `SAME_SOLVE_SEC`), in the same transaction.
- `markAllSeen` (*⋮ → Mark all as seen*) sets it for every cut still at 0.
- The backup writes `seen_ms`; a backup without it (before 4.0) restores its clips as seen.

## Scan state (v9)

How a video's background scan stands (`AnalysisQueue.kt`, 4.0-C), so it survives the app ending. `SessionStore.setScanState` writes it; the queue writes `queued` when a video is put in it, and when the scan ends `no_timer` (*Timer not found* on the card), `asks` (*Choose the timer*) or `''` (done, cancelled or failed). `AnalysisQueue.resume` queues every `queued` or `asks` video again when the app starts, if its file still opens. While the queue holds a job for the video, the job's state is shown instead. It isn't in the backup file: a restored video starts with `''`.

## Hints (v8)

Where a solve may be missing (`MissedSolves.kt`, 3.3-C). `saveHints` replaces a video's hints after every *Find solves*; `mergeHints` keeps the dismissed ones and leaves out a new hint that overlaps one. Nothing links to a cut: a hint isn't shown while a cut lies in it (`hintsToShow`), so adding or deleting a solve never changes the table. `setHintDismissed` finds its row by `seek_sec`.

## How exports link to cuts

- `exports` has **no foreign key to `cuts`**. An export belongs to the cut of the same `footage_id` whose `stop_sec` is within `SAME_SOLVE_SEC` (0.05 s).
- `saveSession` deletes and reinserts every cut in one transaction, so `cuts.id` changes on every save. Never store a cut id anywhere.
- **Moving a cut** in the Edit window is recorded in `pendingMoves`. `saveSession(moves = …)` moves the matching export rows in the same transaction, unless the new spot collides with another cut or another solve's export.
- **Re-export** replaces the earlier record for that solve, except records already in the trash, which stay so they show in *Recently deleted*.
- **(3.2-D) Re-export after a buffer change** (viewer: *Re-export now?*) then offers the old file to the trash. Its record was already replaced, so `setExportTrashed(context, export, stamp)` writes it back with `trashed_ms` set, and it shows in *Recently deleted* like any other.
- **"Exported"** in the UI means: a record exists, it is not trashed, and `canOpen` succeeds on its uri.
- An export whose cut was deleted shows in the Gallery as *Solve deleted* (`GalleryClip(index = -1, solveDeleted = true)`).
- An exported file is a copy. Editing the cut afterwards does not change the file.
- **(3.2-C) A penalty change renames the file**: only the time part of its name changes (`renamedExportName`: `…_Solve_03_11.263s.mp4` → `…_Solve_03_13.263s_plus2.mp4`; title, tag, number and Android's ` (1)` stay). `ClipExporter.renameFile` updates `DISPLAY_NAME`; the name Android really gave goes into `exports.file_name` (`setExportFileName`) in the same IO block. The link (`stop_sec`) doesn't change. A file CubeCut no longer owns (after a reinstall) needs `MediaStore.createWriteRequest` first (`rememberExportRenamer`). A file renamed by hand is left alone. Changing only the time does not rename.

Single-field writes from the Gallery or viewer (`markCutSeen` and `markAllSeen` are the exception that sets no `user_edited`, see *New clips*; `updateCutScramble`, `markCutReviewed`, `updateCutNote`, and from v7 `updateCutTags`, `updateCutPenalty`, `updateCutPads`, `updateCutSharedOn`, and `updateCutTime` for a single solve's time typed in the viewer, through the private `updateCut`) find the cut by `stop_sec` within 0.0005 s, and set `user_edited = 1` where the change is the user's. The exception is `setVideoScramble`: the Gallery's selection scramble switch with every exportable clip of a video ticked sets `footage.scramble_all` and every cut's `include_scramble`, like the editor's all-clips switch, without `user_edited` ([ADR-0013](adr/0013-rescan-merge-keeps-user-edits.md)). The editor and the Gallery never run at the same time (see [ARCHITECTURE](../ARCHITECTURE.md#navigation)), so these writes don't race the editor's delete-and-reinsert save.

## What the Gallery shows (`loadGallery`)

Clips are worked out with `clipsFor(footage, cuts)`: `computeClips` for a session, `singleRange` for a single solve (3.2-F).

(4.0-G) `GalleryData.hints` holds, for each video that opens, the hints still to show (`hintsToShow`, not dismissed): the cards' "N places to check".

- A video is **present** if `trashed_ms == 0` **and** `canOpen` succeeds. The trash flag wins, because Android still opens trashed files.
- **Video present:** all its cuts show as clips, tagged *Exported* or not, plus *Solve deleted* exports.
- **Video gone or trashed:** only clips with a live exported file show (*Export only*). The cuts stay in the database, so re-importing the video brings everything back. Exception (3.2-F): a single solve with no live file still shows, with no picture and a *Video missing* / *In trash* badge, because it has no session card to reconnect or forget it from.
- Checks (`needsCheck`, `warning`, `noClip`) are computed with `computeClips` and `checkCuts` at load time. They are not stored, except `reviewed`.

## Trash

`MediaStore.createTrashRequest` needs a MediaStore uri. `mediaStoreUriOf` derives one from `media` uris and `media.documents` `video:` ids. A video picked through a folder view has none and cannot be trashed from CubeCut. On `RESULT_OK` the app stores `trashed_ms`; restoring sets it back to 0. Android empties its trash after about 30 days. Rationale: [ADR-0008](adr/0008-never-delete-permanently.md).

## Backup file (3.2-G)

`Backup.kt` ([ADR-0015](adr/0015-user-saved-backup-file.md)). One JSON object: `app` = `"CubeCut"`, `format` = `BACKUP_FORMAT` (1), `made_ms`, and `videos`: one object per `footage` row with every column **except** `id`, `uri` and `trashed_ms`, plus its `cuts` (every column except `id` / `footage_id`; `shared_on` as stored) and its live `exports` (`stop_sec`, `time_text`, `file_name`, `offset_sec`, `exported_ms`; no uri) and, since 3.3, its `hints` (missing in older files: none). A trim or per-clip pad that is NULL is JSON `null`.

- **Restore** (`Backup.restore` → `SessionStore.restoreVideo`, one transaction per video): a video that `matchVideo` finds among the saved ones is skipped (the phone wins). Others are inserted with `uri = ''` and `trashed_ms = 0`, their cuts through `saveSession` (so `user_edited` etc. come back as saved) and their exports through `recordExports` with `uri = ''`. `canOpen('')` is false, so they show as missing until reconnected.
- **`matchVideo`**: same name + size, else same size (> 0), with the length within `SAME_LENGTH_MS` (1 s) either way. Used by restore and by **Reconnect videos** (`reconnectVideos`, only videos that can't be opened and aren't trashed).
- **Reconnect exported clips** (`reconnectExports`): exact `file_name` against export records that can't be opened.
- A picked file that matches nothing gets its persisted grant released.
- **Adding a field**: write it in `Backup.write` and read it with a default in `Backup.restore`. A change of meaning bumps `BACKUP_FORMAT`; a newer format is refused.

## Constraints for planned features

- Stored `uri`s are SAF grants that don't survive a reinstall; the backup leaves them out on purpose (an old MediaStore uri can point at another video on a new phone).
- **A new per-cut field** must go through `saveSession`'s insert, `loadCuts` and `withUserFieldsOf` in `RescanMerge.kt`. Follow the recipe in [ARCHITECTURE](../ARCHITECTURE.md#new-per-video-or-per-clip-field-for-example-a-tag-or-a-penalty).
- **A penalty is stored apart from `time_text`** (`plus2_count`, `dnf`), so the solve-only start stays on the raw time. **Changing `time_text`** changes the clip's solve-only start and the name of a later export, but not the export link, because the link uses `stop_sec`.
