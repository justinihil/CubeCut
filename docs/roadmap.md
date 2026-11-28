# Roadmap

This page holds the planned work. The owner listed the items on 2026-11-12. On 2026-11-23 they were ordered into versions and phases, and the decisions marked **Decided** below were made then. Anything else is still open: it gets asked at the start of its phase.

Each phase lists:
- its items, numbered as in the owner's list (`#n`);
- what has been decided;
- the open questions to ask first;
- where to start in the code;
- the rules that limit it;
- its status: ⬜ to do, 🔶 in progress, ✅ done.

When a phase ships, its changes go into `CHANGELOG.md` and the phase is marked ✅ here. When a whole version is released, its section is deleted.

## How to run a phase

**One phase per thread.** Every phase starts in a new Claude Code thread, so each thread reads only what it needs. Also start a new thread:
- after about two fix rounds that didn't work (begin it with a short "where we are");
- for any visual glitch.

Use `/effort medium` for docs-only, release-prep and small phases.

**To start a version** (the first phase of it):
```
Start v4.0: create dev-v4.0.0 from dev, then Phase 4.0-0.
```
**To start a phase** (change the phase id and the branch):
```
Phase 4.0-0 from docs/roadmap.md, branch dev-v4.0.0.
Read CLAUDE.md, then that phase section, then only the files it names.
Ask me the phase's open questions first. Then build it, update the owning docs + CHANGELOG in the same commit,
run assembleDebug + testDebugUnitTest, commit, and give me numbered phone test steps.
```
**During the phase:**
- `Phone test: step 4 failed — <what I saw>` (attach a screen recording for anything visual).
- `All steps pass — close the phase`: Claude marks the phase ✅ here and commits.

**To end a version** (its `-Z` phase):
```
Release prep for v4.0 (phase 4.0-Z).
```
This phase:
- bumps `versionCode` / `versionName`;
- turns `[Unreleased]` in the CHANGELOG into the version entry;
- tidies [known-issues](known-issues.md) and this page;
- lists a full phone test pass;
- opens the PR `dev-vX.Y.0 → dev`.

The owner then merges `dev → main` and tags the release by hand ([release steps](android.md#release-process)).

## Overview

| Version | Branch | Theme | DB |
|---|---|---|---|
| **4.0** | `dev-v4.0.0` | Import several videos; Find solves runs in the background; select several sessions; tags and filters | v9–v11 |

The owner's list had three versions (v3.1, v3.2, v4.0). The first was split in two so that the quick wins, which need no database change, ship first. The owner's "v3.2" (Find solves) became 3.3, released 2026-11-26.

---

## v4.0: Find solves in the background

The plan was agreed with the owner on 2026-11-26: their additions were folded in, with answers A1–E3 of that review. **The owner asked for the whole version to be built in one go and tested at the end.** Each phase is still its own commit, with its docs, CHANGELOG, `assembleDebug` and unit tests. The phone test list comes at the end, grouped by phase (4.0-T). The prerequisite (3.3 measured on several fonts) is met.

What changes for the user: **importing no longer opens the editor and no longer asks "a session or one solve?"**. The user picks one or more videos. Each is scanned in the background, and its result shows on its card.

**Built to change easily** (owner): the tag kinds are one list, giving each kind's choices, whether a clip may differ and whether import asks for it. Every limit is a `private const val` at the top of its file.

### 4.0-0 Remove the setup by hand 🔶
Owner, 2026-11-25 ([ADR-0017](adr/0017-finder-first-setup-by-hand-as-backup.md)). It comes first in 4.0, so 4.0-B has no engine to move. Go given on 2026-11-26 (E2): timers the finder doesn't read yet (Stackmat, GAN, phone timer apps) are added by hand in the editor.
- Goes: `TimerSetupScreen`, `TimerReader`, `DisplayReaders`, the scan in `SolveAnalyzer` (its constants move to `FinderScan`), *Read timer here*, the *Backup* part of the Setup box, and the "by hand" half of the scan report.
- Stays: the solves found with it, and the timer columns, which hold the place of a found timer.
- OpenCV goes too: `FrameSource` hands out `GrayPic`s with the same grey values (OpenCV's formula and rounding), so the readers see the same pictures. Debug APK 66 → 40 MB.
- **Built 2026-11-26**; phone test in 4.0-T. The *Which one is your timer?* question offers *Neither of them* instead of the setup by hand: the solves are then added by hand. A found timer now replaces the place of an old setup by hand.

### 4.0-A Rename "Footage" to "Sessions" 🔶
Owner (C1, C2). Done early, so all new 4.0 text uses the new word.
- Renamed: the Gallery tab, its ⋮ menu, messages, the user guide, README, glossary and ARCHITECTURE. The README's "Footage: 1080p…" rows are about recording quality, so they say *Video* instead.
- **The code keeps `Footage`** (the class, the `footage` table, `footageId`). Renaming it would touch 626 uses in 13 files and the database table, with no visible change. The glossary maps the two names.
- CHANGELOG entries of released versions stay as written. ADRs stay as written too (they record what was true then).
- **Built 2026-11-26**; phone test in 4.0-T.

### 4.0-B Background analysis (ADR and refactor) 🔶
- Move Find solves out of `EditorScreen` (`runFindSolves`) into an app-wide analysis queue: a Kotlin `object` whose work runs in the foreground service.
- A new ADR supersedes [ADR-0011](adr/0011-foreground-service-for-notification-only.md). It also touches [ADR-0003](adr/0003-single-module-plain-compose-state.md).
- Invariant 1 becomes a **per-video lock**: only actions on the video being scanned are disabled.
- Engine ownership (`startUsing` / `stopUsing` / `retireEngine`) moves with the scan.
- The editor's *Find solves* puts its video in the same queue.
- **Built 2026-11-26** ([ADR-0018](adr/0018-app-wide-analysis-queue.md)); phone test in 4.0-T. There was no engine left to move (4.0-0). The extra-time sliders are off while the video is scanned, because the scan saves them.

### 4.0-C Import: several videos, no question, scan by itself 🔶
The owner's additions "import several videos at once" and "no difference between a session and one solve". Answers A1–A4 and B1–B4.
- **Several at once** (A2): the same document picker, with several files allowed. There is no limit; the videos queue. Picking one file works as before.
- **No question** (B): `ImportKindDialog` goes. Every video is scanned, and every found solve is cut and exported the same way, whatever the length of the video (**B3a**, owner 2026-11-26). A one-solve video is a session with one solve: it opens in the editor like any other, and its clip is a copy in `Movies/CubeCut`, so a penalty renames it. To save space, the user can delete the original after export.
  - Why not "the whole file is the clip" (B3b, weighed and dropped): the saving only holds for untrimmed clips. It needs a length rule, which breaks on big puzzles. A single solve couldn't get a missed second solve. The same video could end up as two different kinds. Every later feature would need two kinds of one-solve video.
- **No timer found** (B2), whatever the length: the card says *Timer not found*. Tapping it opens the editor, where the user adds the solves by hand.
- **Imported before** (A1): when several videos are picked, known ones are skipped, and the end says "N were already imported". When one is picked, *Imported before* asks as now.
- **Progress and results** (A3): a slim bar in the app. Expanded, it shows each video as waiting, scanning (with progress), or done with its result ("12 solves", "1 solve", "Timer not found", "2 places to check"). Results stay until cleared, and a *Timer not found* mark stays on the session card.
- A notification says when the queue is done. The app stays navigable; only the actions that would disturb a scan are disabled.
- **Single solves** (B1): the old ones (3.2-F) keep working as they are, and are now listed under Sessions too. The Sessions tab gets a switch at the top: **All · Sessions · Single solves**. *Single solves* means the old ones plus every video with exactly one solve, and the Clips tab's *Single solves* switch means the same. `KnownSingleDialog` stays for when one picked video is an old single solve.
- **The editor's own *Import video* goes** (A4). Import is only in the Gallery.
- Competition videos (B4) mostly hold one solve, so they are one-solve sessions. Their timer (often a Stackmat) is untested.
- **Built 2026-11-26**; phone test in 4.0-T. DB v9 (`footage.scan_state`): waiting scans are queued again after a restart, and *Timer not found* stays on the card. The progress panel sits above the tabs; its rows offer *Cancel*, *Choose*, *Add by hand* and *Open*. A new video's puzzle is no longer asked at import (4.0-F asks it, for one video).

### 4.0-D "New" badge 🔶
DB v10 (`cuts.seen_ms`). It comes before the test rounds (E3), so each batch's results stand out.
- The badge goes away when the clip is opened in the viewer or exported, or with *Mark all as seen*.
- A *New* filter chip.
- **Built 2026-11-26**; phone test in 4.0-T. The badge is blue, on the clip's picture (and "New" in list rows); the filter is *Only new clips*; *Mark all as seen* is in the Gallery's ⋮ menu. A cut you changed or added is never new, and the upgrade marks every saved clip as seen.

### 4.0-E Sessions: select several 🔶
The owner's addition of 2026-11-26.
- **Ticking on the Sessions tab**, as on the Clips tab: a long press starts it, and taps tick cards.
- **Forget** and **Delete video** for all ticked sessions, behind one warning with totals. *Delete video* trashes only the videos (owner): exported clips stay and keep playing, and clips that were not exported leave the Gallery, as for one video. One Android confirmation covers all of them. Videos that can't be trashed (missing, or picked from a folder view) are skipped and named.
- **The last copy of a solve** (owner): an exported clip whose original video is gone already stays in the Clips tab and keeps playing (*Export only*), and *Forget* stays as it is. Its *Delete file* warning now says so: a red note "This is the last copy of this solve. Its original video is deleted." If the original is still in the trash, it says "Restore the video to keep the solve." Then: "Restore within about 30 days, then it's gone for good." It no longer says "the solve stays in CubeCut", which isn't true here. It isn't called irreversible: the file goes to the phone's trash ([ADR-0008](adr/0008-never-delete-permanently.md)).
- **Built 2026-11-26**; phone test in 4.0-T. Also (not asked, flagged to the owner): the Clips tab's *Delete N exported files?* says in red how many are the last copy of their solve, and drops "Solves stay in CubeCut" when none of them does.

### 4.0-F Tags and filters 🔶
DB v11. The owner's additions of penalty, home/competition and cube filters. Answers D1–D7.
- **One list of tag kinds** in `Tags.kt`: puzzle, type, cube and place. Each entry gives its choices, whether a clip may differ and whether import asks for it. A new tag kind is one entry, plus its column.
- **Cube** (D1): the cube model ("GAN 12", "RS3M"…), from the user's own list: typed once, then picked. A clip may differ from its video (D6), as with the puzzle.
- **Place** (D2): *Home*, *Competition* or *Other*. It is set on the video only (D6).
- **Penalty filter** (D5): Any / With penalty / +2 / DNF / No penalty. It covers only penalties set by hand, because the scan doesn't read them.
- **Asked on import of one video** (D3): the scan starts at once, and meanwhile a sheet asks for the puzzle, type, cube and place. *Skip* closes it. With several videos, nothing is asked.
- **Tag many at once** (D4): tick several sessions on the Sessions tab (4.0-E), then *Set tags*.
- The new filters are on the Clips tab only (D7), with the existing ones.
- **Built 2026-11-26**; phone test in 4.0-T. The tag window is the old puzzle window with more sections (`TagKind`). A cube name is typed once with *New cube*; the names already used are the choices. The editor's Setup box sets all four for the video. The cube isn't in exported file names.

### 4.0-G Review possible missed solves 🔶
The hints came in 3.3: they are found without the editor and saved with the video, and a hint's card is its own composable.
- **No popup on its own** (E1, replacing the popup of 2026-11-25): the card and the result list say "2 places to check", and tapping that opens the popup. The editor opens, at that place, only if the user takes a hint up. *Nothing here* works from the popup too.
- **Built 2026-11-26**; phone test in 4.0-T. The card shows an amber "N places to check" (grid: a badge; list: a button), the progress list *Check N*. *Go there* opens the editor with the playhead exactly at the hint. The editor still shows its hints in the list, as in 3.3.

### 4.0-H The editor as manual mode 🔶
- What CubeCut can't read is added here by hand, since the setup by hand is gone (4.0-0).
- The editor stays for clipping by hand. It is reached from Sessions ⋮ *Open in editor* and from a *Timer not found* card (4.0-C).
- **Built 2026-11-26**; phone test in 4.0-T. The editor shows *Couldn't find the timer* at once for a video whose background scan found none (also after a restart), with the way to add solves by hand. The card's *Timer not found* goes once solves were added.

### 4.0-I Clip settings for many clips at once 🔶
The owner's addition of 2026-11-26, before testing: the scramble and the extra time were per clip (viewer) or per video (editor).
- **The Clip settings button** (light blue with a gear, at the right of *Sort* / *Filter* on both tabs; hidden while ticking, so the bar's *Clip settings…* is the only one; owner, 2026-11-26/27, moved out of *⋮ → Settings*): what new videos start with, and, ticked, *Also apply to all videos now*, after the warning lines.
- **Ticked clips or ticked sessions → Clip settings…**: the same for just those. The sliders show at once (owner); the extra time changes only if one is moved, and *Keep as it is* leaves the scramble.
- **Clips with their own setting are overwritten** (owner): whole videos take it as their video setting, so every clip follows it again (no `edited`); clips of a partly ticked video are changed one by one (`edited`).
- **Exported files don't change** (owner: point it out): the window says how many are exported and that exporting again updates them.
- Old single solves are left out (their clip is their trim).
- **Built 2026-11-26**; phone test steps 39 to 44.

### 4.0-J The owner's notes before testing 🔶
The owner's notes of 2026-11-26, after the first phone test step:
- **The batch as a whole:** the slim bar and the notification show "1 scanning, 3 waiting · 34%" (the share weighted by video length), not one video. It said "2 of 10 videos done" until 2026-11-27: after a second pick the owner read it as videos added that weren't picked. Each video's own progress is a thin bar on its card.
- **"It's fine" in the viewer** looks as in the editor: the amber outlined button, then a green "✓ Checked" (`ItsFineButton`, `CheckedMark`, shared).
- **A cancelled video stays in Sessions** (owner: makes sense, preferred), and its row offers **Scan again** besides *Open*. **Find solves** for ticked sessions; **Cancel all** while a batch runs (asks first; the videos stay).
- **Hold and drag** to tick sessions, as on the Clips tab.
- **A filter on the Sessions tab** (owner; the tag filters stay on the Clips tab, D7): *Not scanned*, *Timer not found*, *To check* (the last two added alongside, flagged to the owner). (Owner, 2026-11-27) *To check* covers solves to check as well as places to check: both need the same look.
- **The selection bar on the Sessions tab** (owner, after a first try): ✕, count, All/None (of what the filter shows) and **Find solves** filled; below, *Set tags…* and *Clip settings…* as light blue (tonal) buttons side by side; then, each half the width, *Delete videos* (red outline with the bin) and *Forget* (red outline), away from Find solves. Colours (owner, 2026-11-27): filled blue does the work, light blue changes things, red outline removes something; the solid red button is only in the warning window. The Clips tab's bar matches: *Export* alone on the top row, *Set tags…* and *Clip settings…*, then one full-width *Delete exported files*. A delete that can't happen says why when tapped, like the viewer's *Delete file* (owner).
- **Built 2026-11-26**; phone test steps 45 to 50.
- **Owner notes of 2026-11-27:** the editor's Setup box is gone (padding with *Clip settings*, tags with *Set tags…* in the Gallery); "Extra time" is now **Padding**, with an ⓘ; the viewer's *Delete exported file* (a tap with nothing to delete points to the editor for a find that isn't a solve); **Not a solve** next to *It's fine* in the viewer (red outline, a warning, then the solve goes; no Undo); left-over finds are never saved (DB v12 deletes the saved ones). Phone test steps 56 to 64.

### 4.0-S Faster Find solves (owner, 2026-11-27) ✅
- **Step 1, measured on the phone (2026-11-27):** the timing split in the debug *Scan report*; only the timer's area made for found timers; frames nothing needs left out ([algorithms § 2](algorithms.md#2-where-frames-come-from)). csTimer 01 106.8 → 96.9 s, QiYi 01 151.4 → 139.8 s, same results; the decoder takes two thirds of it.
- **Step 2, built (2026-11-27), not measured on the phone:** the report says which picture source was used and why (the check now tries all three moments); the QiYi tracker gets the part around the timer; the progress panel's **Copy report** tells, for every video, where its time went. **Half-second steps taking the nearest frame of the video's lower layers**: first switched off (one false solve on QiYi 01, one cut 2 frames off on flowtimer 01), both causes fixed, now on ([algorithms § 2](algorithms.md#2-where-frames-come-from)). PC check: QiYi 01 old code 21.1 s reading, new 21.4 s, so the window costs nothing; on the phone the run after step 1 was slower all over (the unchanged timer search 75 % slower), so totals can't be compared between those two runs.
- **Next, one at a time, each against all reference videos** (they change which pictures are read): the cut search starting where the running clock says the stop is (about 10 frames instead of 30 per solve); 4 s instead of 2 s steps while a found time is held (reset, scramble and solve take at least 6 s); longer steps while the timer runs with the same start (weakens the clock check and the hints, so last).
- **Step 3 (2026-11-27):** the timer of the video before is looked for first (`TimerHint`): on the phone every one of five single solves was found that way, but finding still took 15 to 20 s, so after a hint the learning no longer searches for more runs, and watching and learning read the timer's area only. The next picture made while this one is read (`PrefetchSource`): measured, switched off ([algorithms § 2](algorithms.md#2-where-frames-come-from)).
- **Phone, build 4a93095 (2026-11-27):** five single csTimer solves in a row: finding 8.5 to 10.4 s from the 2nd video on (16 to 22 s before), the batch 129 → 91 s, nothing changed in the solves. Earlier: csTimer 01 scan 106.8 → 71.6 s, QiYi 01 151.4 → 135.8 s (decoder 61 → 46 s, grey 28 → 2 s).
- **Parked** (owner agreed, 2026-11-27: testing first): with a hint, walk the video forward instead of jumping between places (2 to 3 s per short video); a second try at reading ahead with a bigger area made ahead.
- Not planned: fewer block sizes when reading, reusing a reading when the picture looks the same, halving the gap for the cut (blended frames), smaller pictures.

### 4.0-S2 Find solves: videos without a timer (owner, 2026-11-28) 🔶
The owner: Find solves is the heart of the app; a video whose timer isn't found must not take minutes and look stuck. All of it in 4.0. The current search is drawn in https://claude.ai/artifact/FuyTuEpfSWfMSzXfdvMBy6 (steps F0 to F7).
- **Before (2026-11-28).** Phone, a batch of 10 (`D:\CubeCutRef\work\m0`): the three videos without a readable timer took 694 of 1134 s and gave nothing (a 30 s competition clip with a hidden Stackmat 470 s, `ref_notimer_01` 180 s, Messenger 45 s). Almost all of it is reading, not getting pictures (19 of 470 s). PC, the app's order (`ReferenceVideoTest.measuresFindingLikeTheApp`): about 500 whole pictures for each of them, against 16 to 174 for a found timer; the seven-segment rows (F4) are 70 % of the 30 s clip (0.5 s a picture on a busy picture). The phone reads about 8 to 9 times slower than the PC.
- **Order agreed:** 1 a bar that moves while looking, and the report's missing parts; 2 stop early when nothing digit-like is seen; 3 a cheaper F4; 4 the hybrid (find the place by any number that changes, final times too, at one picture a place; then make sure it runs); 5 lower limits, set from what 4 needs; 6 fewer places for short videos if still needed; 7 the Stackmat clip.
- **Step 1, built (2026-11-28):** while looking, the bar shows the share of every place the search would look at if it found nothing (`solidPlaceBudget`, `segmentPlaceBudget`); it was 0 to 10 % before. A batch weighs each video as about 20 s of looking plus 0.12 s per second of video (`scanCostSec`), not by its length alone. The *Copy report* adds *Getting ready*, the seven-segment part of finding, *Last check* and *Saving*: about 126 s of the 30 s clip and 76 s of csTimer 01 were in none of its lines.
- **Steps 5 and 3a, built (2026-11-28; owner: about twice what the hardest reference video needed):** normal digits look at 40 places at half size and 16 at full size (were 160 and 64; the hardest needed 19, none needed full size), seven segments at 68 + 20 blurred (were 120 + 40; the hardest QiYi needed 34). A video without a timer: about 124 places instead of up to 384. PC (`work/s7`): every reference video the same (normal digits also with steps 2 frames off either way, final times 75/75 both ways, QiYi 55/55, 30/30, 6/9, digital 4 4/4, camera moves on csTimer 01 25/25); the ten videos: `ref_notimer_01` 20 → 8 s and 563 → 178 pictures, Messenger 5 → 2 s, the 30 s clip 61 → 42 s (it pays for F4 on a busy picture), found videos unchanged and every solve the same.
- **Report, built (2026-11-28):** the phone's step-1 batch had 49 s, 50 s and 19 s in no line (Saving and Last check were under 0.05 s). The *Copy report* now also says *Waiting to start*, *Closing the video*, *Back to the queue*, *In none of these lines*, the battery's temperature at the start and the end and the worst heat status (no permission needed), and the build in its first line (`BuildConfig.COMMIT`, "+" = uncommitted changes).

### 4.0-T Phone test and test rounds ⬜
- First, one numbered phone test list for 4.0-0 to 4.0-H, grouped by phase: given to the owner on 2026-11-26 and kept in `D:\CubeCutRef\v4.0-phone-test.md` (54 steps).
- Then the owner imports batches of videos and puts the ones that didn't work in `D:\CubeCutRef`. Claude finds out why (answer first), and the fixes follow as separate commits. Each round starts a new thread: `Test round: <files and what happened>`.
- The scan, the reader and `computeClips` change only in these rounds, and are checked against the known-good footage ([algorithms §8](algorithms.md#8-reference-measurements)).
- The first thing to measure is whether the finder trusts a timer it sees for only one solve.

### 4.0-K Groups and the time filter 🔶
The owner's notes of 2026-11-27, wanted in 4.0. *Sort → Group by* None / Session / Puzzle / Time on the Clips tab; *Group by month* in the Sessions *Sort* menu. Headings fold on a tap (remembered). Time bands by the size of the time, one rule for every puzzle (1 s below 20 s, 5 s below 1:00, 10 s below 2:00, then 1 min). *Filter → Faster than [__] s*. Also: *Type* became *Solve type*, a clip's tag window says the place is set per session, the progress bar counts what is left. Not asked, flagged: *Clear all* didn't clear the 4.0 filters (new only, cube, place, penalty) and didn't count them; it does now.

### 4.0-Z Release 4.0 ⬜

---

## Deferred earlier (not planned)
- Keep every export copy. Both delete buttons would then need to delete all copies.
- A "too short" time check. Rejected, because some solvers get very short times.
- Remember deleted cuts, so a re-scan can't bring them back.
- Permanent cut ids.
- Large-gallery speed: `canOpen` runs on every resume, and the editor list isn't lazy.
- Splitting `Gallery.kt` and `EditorScreen`.
- TalkBack labels and 48 dp touch targets.
- An exact (re-encoding) export option.
- A cropped ORB search near the last position (declined).
