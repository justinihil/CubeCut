# Known issues and limitations

Things that are wrong, fragile or untested today. When one is fixed, remove it from this page in the same change. User-facing limits that need no code reading are in the [user guide](user-guide.md#things-to-know).

## Defects and inconsistencies (code changes needed)

| Issue | Where | Notes |
|---|---|---|
| **`lintDebug` fails** with 2 errors | `Gallery.kt:3617-3618`: `PlayerView.setShutterBackgroundColor` / `setKeepContentOnPlayerReset` are Media3 `@UnstableApi` without `@OptIn` | 70 warnings and 47 hints besides (checked 2026-11-26, 3.3.0). Add `@OptIn(UnstableApi::class)` or a lint baseline |
| **Dead template theme** | `app/src/main/java/com/example/cubecut/ui/theme/{Color,Theme,Type}.kt` | Unused, but declares a second `CubeCutTheme`, so a wrong auto-import is possible. An earlier changelog said it had been deleted. Delete it |
| **Thin tests** | `app/src/test`, `ExampleInstrumentedTest` | Unit tests cover the pure functions (`computeClips`, `parseTimeSeconds`, `mergeRescan`, `checkCuts`, the v7 field helpers), and since 3.3-C the finders, readers and scans on drawn pictures. The reference videos are run on the PC only when asked (`ReferenceVideoTest`, not in CI). The SQL (including the v7 and v8 upgrades) and the UI are checked only on the phone. See [app/README § Tests](../app/README.md#tests) |
| **Find solves estimate is too high** | `estMin` = 0.15 × video length (`MainActivity.kt`); measured 0.09 (csTimer) and 0.11 (QiYi) on the owner's phone | Slower phones need more |
| **Template leftovers** | `res/values/colors.xml` `black` / `white`; `keepRules/rules.keep` | Harmless |

## Limitations by design

- **Found automatically: numbers with a decimal point that run and stop**, in solid digits (csTimer, phone apps) or seven segments (QiYi). **A solid-digit timer that hides its running time or shows whole seconds** is found by its final times: it needs at least three different times in the video, all with decimals; a misread time is harder to notice; a solve whose time never shows mostly gets no hint when the running time is hidden; a second solve with exactly the same time straight after it, without a 0.00 between, counts as the same when the running time is hidden. Measured on 20 real solves (flowtimer, a csTimer clip, csTimer in "digital 1" and "digital 4") and 75 simulated ones; csTimer's "solve" screen worked on the owner's phone (2026-11-26) but has no key. **A seven-segment timer that hides it** (csTimer's "digital 4" and "digital 5") is found the same way, but only when nothing else was found, and only on one video so far (4 solves, dark theme; [algorithms](algorithms.md#3-reading-the-timer)). Measured on one desk, with csTimer (default look, "digital 1" and "digital 4") and flowtimer.io. **csTimer's "digital 2" and "digital 3" fonts are mostly not found** (*Couldn't find the timer*): their digits break into many pieces (drawn, 4 and 2 of 18 read). csTimer's "digital" fonts with the running time shown, and on the light theme, are untested (they would be looked for as a running seven-segment timer). A timer that **changes its time when it stops** (flowtimer with a smart cube) gets some solves flagged although they are right. Digits under about 24 px, strong blur and a screen turned far over don't read. **Seven-segment:** measured on one QiYi only (three sessions, all used to develop it); a push of more than 6 digit heights between two looks (10 s), or a push that also turns the timer a lot, can lose what is on its other side; Stackmat, GAN, LED digits and times over a minute are untested; digits under about 22 px, strong blur and a display turned past about 55° don't read. With csTimer and a QiYi in view, csTimer is used; if csTimer hides its running time, it is used when the search saw its final times early on, else a running QiYi can be taken. +2/DNF marks next to the time and times of an hour or more aren't handled, and aren't planned (owner, 2026-11-26: every timer shows penalties differently); penalties are set by hand.
- **A found timer needs a fixed camera** (owner, 2026-11-24). A solid-digit timer is read in one area, with room around it; a seven-segment timer is followed when it slides, which would also follow small camera moves (not measured). Small moves are fine ([algorithms](algorithms.md#3-reading-the-timer)); after a big one the later solves can be missed without a warning.
- **What the finder doesn't read has no automatic way since 4.0-0** (the setup by hand is gone): Stackmat, GAN, LED and phone timer apps are untested, the Messenger copy of a phone timer app (9 px digits, slanted) isn't found, and csTimer's "digital 2" and "digital 3" mostly aren't. Their solves are added by hand in the editor.
- **Hints show only some missed solves:** those where the timer was seen running, or couldn't be read for long. Sessions scanned before 3.3 get hints with their next *Find solves*.
- **Held time must stay on screen for about 1.5 s or more.** Timers that blank immediately aren't supported. A timer that updates its running display slower than every 0.5 s could look stopped; this has not been seen.
- **Deleted cuts can come back** on a re-scan ([ADR-0013](adr/0013-rescan-merge-keeps-user-edits.md)).
- **No per-frame fallback** to the saved corners when tracking fails on a frame; that frame is skipped.
- **The Edit window's timer zoom doesn't follow the camera.** It crops around where the timer was found, with a margin of half the timer's width. If the phone moved more than that, the timer is off the picture: tap *Whole frame*.
- **Lossless clips start on a keyframe**, up to about 1 s early. A keyframe over 64 MB can't be exported.
- **Export has no foreground service.** Leaving the app during a long batch export could interrupt it. The screen is kept on.
- **Find solves in the background lives in memory** ([ADR-0018](adr/0018-app-wide-analysis-queue.md)). Swiping CubeCut away should no longer stop it while Android keeps the foreground service (not tested on the phone); vendor battery savers may still stop it. When Android ends the process, the scan in progress is lost; the videos still waiting (and the one being scanned) are queued again at the next start (`scan_state`, 4.0-C), and one that asked which number is the timer is scanned again to ask.
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
- backup and restore (3.2-G) on a second phone, and large backups. The backup file, a restore after a reinstall and both *Reconnect* buttons passed on the owner's phone (3.3 test pass, 2026-11-26);
- large galleries;
- whether the export buffer grows as intended on a keyframe over 4 MB (it relies on `readSampleData` throwing `IllegalArgumentException`);
- a signed release build on a second phone;
