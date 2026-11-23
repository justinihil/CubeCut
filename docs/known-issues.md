# Known issues and limitations

Things that are wrong, fragile or untested today. When one is fixed, remove it from this page in the same change. User-facing limits that need no code reading are in the [user guide](user-guide.md#things-to-know).

## Defects and inconsistencies (code changes needed)

| Issue | Where | Notes |
|---|---|---|
| **`lintDebug` fails** with 2 errors | `Gallery.kt:3586-3587`: `PlayerView.setShutterBackgroundColor` / `setKeepContentOnPlayerReset` are Media3 `@UnstableApi` without `@OptIn` | 65 warnings and 46 hints besides (checked 2026-11-23, 3.2.0). Add `@OptIn(UnstableApi::class)` or a lint baseline |
| **Back on the timer setup screen leaves the editor** | `TimerSetupScreen.kt` has no `BackHandler`, so Back reaches the editor's (`leaveToGallery`) | Only the on-screen Cancel cancels setup |
| **Dead template theme** | `app/src/main/java/com/example/cubecut/ui/theme/{Color,Theme,Type}.kt` | Unused, but declares a second `CubeCutTheme`, so a wrong auto-import is possible. An earlier changelog said it had been deleted. Delete it |
| **Thin tests** | `app/src/test`, `ExampleInstrumentedTest` | Unit tests cover the pure functions (`computeClips`, `parseTimeSeconds`, `mergeRescan`, `checkCuts`, the v7 field helpers) and, since 3.3-A, `SolveAnalyzer.scan` on a simulated display. The SQL (including the v7 upgrade), the digit reader on real frames and the UI are checked only on the phone. See [app/README § Tests](../app/README.md#tests) |
| **Stale code comments** | `SolveAnalyzer.kt:53-57` (describes walk-back only, but the code bisects first; mentions a prototype test that isn't in the repo) | Fix when touching those files |
| **`TimerStore.clear` shim** | `TimerReader.kt`, called at start-up | Deletes 1.x files. Can go once nobody runs 1.x |
| **Find solves estimate is too high** | `estMin` = 0.3 × video length (`MainActivity.kt`) | Dates from before the 4× speed-up |
| **Two meanings of `102.34`** | Setup box: 1:02.34. `parseTimeSeconds` (Edit window): 102.34 s | Documented to users. Unifying would be a behaviour change |
| **Template leftovers** | `res/values/colors.xml` `black` / `white`; `keepRules/rules.keep` | Harmless |

## Limitations by design

- **Only seven-segment fonts**, and setup needs the user to mark the display and type a number ([ADR-0014](adr/0014-display-reader-seam.md)). +2/DNF marks next to the time and times of an hour or more aren't handled.
- **Mixed digit sizes probably don't read** (for example csTimer's default look, where the digits after the point are smaller). Every digit is measured in one shared height band (`refineBand`, then `classify` with `bandTop` / `bandBottom`), so a small digit's segments are looked for in the wrong rows. The spacing is learned from the big digits too. Expected from the code, not yet tried on footage. Target for [3.3-D](roadmap.md).
- **Held time must stay on screen for about 1.5 s or more.** Timers that blank immediately aren't supported. A timer that updates its running display slower than every 0.5 s could look stopped; this has not been seen.
- **Misread digits can win the vote** on short holds. A held time read without its last digit can become a separate solve, or even replace the real time, which the *too fast* check then flags ([algorithms](algorithms.md#1-finding-solves)). Reproduced in the simulator; planned for 3.3-B.
- **Deleted cuts can come back** on a re-scan ([ADR-0013](adr/0013-rescan-merge-keeps-user-edits.md)).
- **No per-frame fallback** to the saved corners when tracking fails on a frame; that frame is skipped.
- **The Edit window's timer zoom doesn't follow the camera.** It crops around the corners saved at timer setup, with a margin of half the timer's width. If the phone moved more than that, the timer is off the picture: tap *Whole frame*.
- **Lossless clips start on a keyframe**, up to about 1 s early. A keyframe over 64 MB can't be exported.
- **Export has no foreground service.** Leaving the app during a long batch export could interrupt it. The screen is kept on.
- **Swiping the app away stops Find solves.** Vendor battery savers may too ([ADR-0011](adr/0011-foreground-service-for-notification-only.md)).
- **Portrait only**, with no landscape layout ([ADR-0010](adr/0010-portrait-lock-config-changes.md)).
- **The backup holds no videos** ([ADR-0015](adr/0015-user-saved-backup-file.md)). After a restore, videos and exported clips must be reconnected by picking them. Restore never overwrites a video this phone already has. An exported file renamed by hand can't be matched.
- **Trash needs a MediaStore uri.** Videos picked from a folder view can't be trashed from CubeCut. Persisted grants are capped at 512.
- **A video that changes size or length** (re-encoded by another app) is treated as a new video ([ADR-0005](adr/0005-video-identity-heuristics.md)).
- **The timeline shows at most 12 film-strip pictures** (`THUMB_COUNT`), so long videos look coarse at high zoom.
- **Large files:** `MainActivity.kt` and `Gallery.kt` are big. The editor list isn't lazy, and `loadGallery` calls `canOpen` on every resume. Large galleries (hundreds of clips) are untested for speed.
- **Accessibility:** no full TalkBack labelling or 48 dp touch-target pass.
- **UI strings are in Kotlin**, not `strings.xml`, so there is no localisation.

## Not verified

Only the Samsung Galaxy S25 Ultra has been used. These have not been checked:
- other phones, Android 11–14 (the `dataSync` service path) and the backup exclusion on a real transfer;
- Android 15's 6-hour background limit (`onTimeout`);
- HDR or 10-bit footage, 4K, frame rates other than 30 and 60 fps, the frame-source fallback actually triggering;
- a timer showing 2 decimals (supported in code, never tried);
- renaming an exported file CubeCut no longer owns (after a reinstall: `createWriteRequest`), 3.2-C;
- **backup and restore (3.2-G) has not been tried on a phone at all**: the backup file, a restore after a reinstall or on a second phone, both *Reconnect* buttons, and large backups. The owner moved this test to the 3.3 test pass;
- large galleries;
- whether the export buffer grows as intended on a keyframe over 4 MB (it relies on `readSampleData` throwing `IllegalArgumentException`);
- a signed release build on a second phone;
- light mode on `TimerSetupScreen.kt`. It was looked at during the 3.0.0 test pass but not reviewed in detail.
