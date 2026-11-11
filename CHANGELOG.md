# Changelog

All notable changes to CubeCut are listed here, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

> **About the older entries.** This file was created with version 2.0.1. The entries for 1.0.0, 1.1.0 and 2.0.0 were reconstructed afterwards from `docs/CUBECUT_PROJECT_GUIDE.md` and the two change records in `docs/`, so they describe the milestones rather than every small edit, and their release dates were not recorded. From 2.0.1 on, add to **Unreleased** as you work and rename it to the version number when you release.

## [Unreleased]

Nothing yet. Planned for 3.1: see *Next build* in `docs/V3_HANDOVER.md`.

## [3.0.1] - 2026-11-11

A patch on top of 3.0.0 (developed on branch `dev-patch-v3.0.1`, branched from `dev`). How solves are found, how digits are read and how clips are calculated did **not** change.

### Changed
- **Playing the video no longer changes the selected solve** in the editor. The selected card stays the one you chose while the video plays on, and the list doesn't scroll by itself. The arrows step from the selected solve.
- **Tapping the timeline selects the solve there** and scrolls the list to its card, whether the video is playing or paused. Dragging along the timeline moves the playhead, and the solve is selected where you let go. Tapping a spot with no solve keeps the current selection.
- **The timeline has a new look:** markers that size themselves to the room they have (thin when many solves share the timeline at zoom 1, thicker with a dark edge when zoomed in or with few solves), a solve that needs a check has an **amber marker** (the same amber as "check" on the cards), rounded clip bars on a faint lane with a small gap between clips, the selected solve's clip outlined in white with a light band over it, a red playhead with a small triangle on top, and rounded corners.

## [3.0.0] - 2026-11-10

**Saved sessions and the Gallery** (developed on branch `dev-v3.0.0`, branched from `dev`): CubeCut remembers your sessions, and a Gallery of all clips is built on top of that. This covers **Phase 1 (saved sessions)**, **Phase 2 (the Gallery, with the full-screen clip viewer)** and **Phase 3a-3c (re-scans keep your edits; the editor only finds solves while choosing and exporting moves to the Gallery; checks; the new look and dark mode)**. All phases are built and running on the phone (Galaxy S25 Ultra); the planned testing turned into the review fixes below, and a full test pass on the merged `dev-v3.0.0` (2026-11-10) brought the last fixes. How solves are found, how digits are read and how clips are calculated did **not** change. A code review and the fixes from testing followed (*Review fixes* below); from 3.0.0 CubeCut needs **Android 11 or newer**.

### Test pass fixes (2026-11-10)

From a second code review and the full test pass on `dev-v3.0.0`. How solves are found, how digits are read and how clips are calculated did **not** change.

**Changed**
- **Video sound behaves like other video apps:** music from another app pauses while a clip or the editor plays, a call or another app's sound pauses CubeCut, and unplugging headphones pauses playback instead of switching to the speaker.
- "Exported as …" in the viewer and the timer dot on the Setup box line use the theme's green and amber, so they are readable in light and dark mode.

**Added**
- **"Too fast" check:** a solve that ends less than 6 seconds after the one before it is flagged *check*, both solves of the pair (nobody can reset the timer, scramble and solve that quickly). It catches misreads where the time still on the display loses its last digit, for example 11.517 read as 1.151. *It's fine* clears it as usual.

**Fixed**
- **The "to check" number after Find solves didn't match the list.** The status line and the notification now count solves and checks exactly like *Only solves to check* (the scan's notes and the timing checks, after a re-scan's merge).
- **Tapping or dragging on the editor's timeline selected nothing or the wrong solve** after opening a saved video, after Find solves or after a delete (until the zoom was moved).
- **Exporting a solve again removed an earlier, deleted copy from *Recently deleted*.** A file in the trash now stays listed and can still be restored.
- **The file name shown after exporting again was wrong** when Android had added " (1)" to it. CubeCut now records the name the file really got.
- An export whose file Android couldn't finish is now reported as failed and removed, instead of counting as done.
- **Saving a new timer setup or importing a video while *Read timer here* was still reading** could free the reader's memory while it was in use (a rare crash). Each timer setup is now freed only after its last reading has finished.
- Exported file names always use the digits 0-9, whatever language the phone is set to.

**Clean-up** (no visible change)
- Shared names for values that were repeated in several files (the "added by you" note, the 0.05 s same-solve tolerance, picture names); a duplicate function in `RescanMerge.kt` and unused highlight words removed; outdated comments corrected.

**Checked**
- The OpenCV libraries are 16 KB aligned (needed by newer Android phones), and the app still has no internet permission.

### Review fixes (2026-11-09, branch `dev-v3.0.0-review-fixes`)

A review of all the code (unused code, leaks, things that would cause trouble later), applied in batches together with the requests from testing. Merged back into `dev-v3.0.0`. How solves are found, how digits are read and how clips are calculated did **not** change.

**Added**
- **Hold and drag to choose clips** in the Gallery: hold a clip, then drag over others to tick them; the list scrolls by itself near the top and bottom edge, with a short vibration at the start. A tap still ticks one clip.
- **Filter chips** under the Sort line on the Clips tab (*Video: …*, *Exported only* / *Not exported only*, *Needs a check*), each with ✕, and **Clear all** when two or more are on.
- **The viewer's action row:** **Export** (or *Export again*), **Editor** and **Delete file**, side by side with icons. A greyed-out button says why for 5 seconds when tapped.
- **Exported files whose solve was deleted stay in the Gallery**, tagged *Exported* and *Solve deleted*. They play from the file, the file can be deleted, and Undo (or a new cut at the same spot) makes them a normal solve again.
- **Deleting an exported solve in the editor asks first:** *Delete solve, keep file* or *Delete solve and file* (the file goes to the phone's trash, with Android's prompt). Solves that were not exported still delete at once with Undo.
- **Visual warnings for Delete video and Forget this session:** the video's picture and title, then short lines with a green ✓ (stays), red ✕ (goes) or blue ↺ (can be restored), and a red button. Forget adds "Can't be undone".
- **The Edit window shows the frame the cut is on**, with its time, and updates after each move. An exported solve shows "Already exported: changes here don't change the file".
- **The selected solve follows the playhead** while playing and when the timeline is tapped or scrubbed, and the list scrolls to its card. A solve's scramble counts as part of it even when its clip leaves the scramble out. With *Only solves to check* on, other solves are skipped.
- **The zoomed timeline keeps the playhead on screen**: while playing, after a jump (a card, the arrows) and when the zoom changes.

**Changed**
- **+ Cut at playhead is now "Add a missed solve at the playhead"**: a blue-bordered button with a round plus, and an "i" next to it that explains where to put the playhead and what happens.
- **Adding a missed solve asks for the solve time** instead of reading the video (that took about 20 reads and was slow). A spot inside an existing solve is refused with *Go to solve N*; a time that would overlap the solve before is refused the same way. It, and the two switches, only show once Find solves has run on the video.
- **Editor layout:** pinned are the video, timeline, the review bar with **arrow buttons** instead of *‹ Prev* / *Next ›*, the Undo bar, a full-width **Add a missed solve at the playhead**, and **Scramble in all clips** and **Only solves to check** side by side. The Setup box (folded: "Timer ready · 2.5 s before · 2.5 s after", **Change**), **Find solves** (full width, *Find solves again* when there are solves), *Mark all N as checked* and the list scroll. With nothing selected, the arrows step from the playhead.
- **Edit window:** two parts, *Solve time* and *Where the cut is*; the move buttons read **◂ 0.5s ◂ 0.1s** (earlier) and **0.1s ▸ 0.5s ▸** (later); *Jump to this frame* is now *Show it in the video*.
- **Moving a cut keeps its export link** (the export record moves with it), so the solve stays *Exported*.
- **Footage ⋮ menu:** *See its clips* (its chip on the Clips tab undoes it), Reconnect, Export clips…, Restore, Rename, then **Delete video** (was *Move video to trash*) and **Forget this session** as red buttons at the bottom. *Show / hide its clips* and *Open in editor* were removed (a tap on the video opens the editor).
- **Export window:** says how many were exported before, explains that exporting again makes a second copy, and offers *Export only the N new*, *Export all N, X again* and Cancel. While choosing, the header says "N already exported".
- **Grid cells:** the solve time moved to the lower right, above *Solve N of M*, so tags no longer cover it.
- **About window:** the logo next to the title, the credit and "Find me on TikTok, Instagram and Facebook: DadCuber" largest, the licence bullets smaller.
- **Android 11 or newer is now required** (`minSdk 30`, was 24). On Android 7–9 exports went to a folder only CubeCut could see, which was erased on uninstall, and deleting from CubeCut needed Android 11 anyway.
- **No backups:** CubeCut's data is left out of Android's Google backup and phone-to-phone transfer (`allowBackup="false"`, `data_extraction_rules.xml`).
- **Find solves in the background** is labelled *media processing* on Android 15 and newer (*data sync* before), and stops cleanly if Android's daily limit for background work runs out.
- **Portrait only**, and dark-mode, font-size and similar changes no longer restart the editor or a running Find solves.
- **Pictures:** made at the small size directly; the picture folder is kept under about 100 MB; a half-written picture is never used; Forget also removes a video's viewer pictures and the pictures of its exported files.
- **The editor does less work while the video plays:** only the time and the red playhead redraw, and nothing runs while paused.

**Fixed**
- An export's record was lost if the clip viewer was closed while the export was finishing.
- The video player view in the clip viewer kept hold of the player after leaving the screen.
- The timer engine's native memory is freed after the last background read, not while one could still be using it.
- Two exports running at the same time could share one buffer.
- The editor said *Already exported* for a file that had been deleted in another app.
- A white flash before the first screen in dark mode.

**Removed**
- The automatic reading behind + Cut at playhead (`SolveAnalyzer.locate`, `analyzeOne`, `ManualCut`, `MANUAL_BACK_MAX_SEC`), `Footage.includedCount`, `SessionStore.updateCutFlags`, the editor's unused *select to export* path (`ui.selectFootage`), and all code for Android 7–10 (the app-only export folder, "needs Android 11" checks).
- `res/xml/backup_rules.xml` and the unused `ui.theme` folder.

### Testing phase: navigation, wording and audio (2026-11-09)

**Added**
- **A scramble button on every solve card in the editor** (crossing arrows, left of *Edit*): blue with a blue border when the clip includes the scramble, grey and crossed out when it doesn't (`ScrambleButton`). Tapping it counts as an edit, so a re-scan keeps it. It is disabled for *left over* cuts and while Find solves runs.
- **"You were just here" highlight.** Coming back from the full-screen viewer, the Clips tab scrolls so the clip you were **last on** (not the one you opened) is on screen, then it gets a blue border and a light blue glow that comes up quickly, holds and fades (about 1.7 s). Coming back from the editor does the same for the video on the **Footage** tab. Works in grid and list view; nothing is shown if the clip is hidden by the current filter.
- **Shared note style** (`Notes.kt`): `InfoNote` (a small round symbol and one or two short lines on a soft tinted card: blue = information, amber = check, red = problem), `InfoButton` (a round "i" that opens a short window), `BulletLines`, `LeadAndBullets`, `kindOfMessage`. Colours come from `MaterialTheme` and `AppColors`.

**Changed**
- **"Include the scramble in all clips" moved again:** out of the Setup box (the old *3. Scramble*) to its own line directly above *Only show solves to check*. It shows as soon as a video is open. Flipping it sets **every** clip; a card's own scramble button can then change one clip again.
- **The editor's Gallery button and Back now open the Footage tab** and point out the video you were editing. *View* on a solve card still opens that solve's clip in the viewer.
- **Wording pass over the whole app.** Shorter lines (one idea each, say what to do), long explanations moved behind "i" buttons or into bullet lists, and messages shown as note cards: the Setup box, the Find solves window and progress card, the notes after a scan (*No solves found* with a short *What to try* list), the help for the solve cards, the Edit window, the Gallery's message banner (now a note with an *OK* action), the empty Gallery, and the export, trash, forget, reconnect and *Recently deleted* windows. The About window keeps every licence and credit term, now as four bullets.
- **Timer setup screen:** a "Step 1 of 2" / "Step 2 of 2" heading with one-line instructions, a hint under the number box (for 1:02.34 type 102.34), and its messages as note cards.
- **Notifications** are shorter ("Finding solves", "Reading the timer: 42%", "Find solves finished").

**Fixed**
- **Video audio kept playing after leaving the app** (home, another app, screen off), in both the editor and the Gallery's clip viewer. Both players now pause as soon as CubeCut is no longer in front. Playback does not start again by itself when you come back.

### Phase 3c: the new look

**Added**
- **Dark mode you can switch:** Gallery, ⋮, Settings, **Appearance**: *Same as the phone* (default), *Light* or *Dark*. The status bar icons follow.
- `Theme.kt`: all the app's colours in one place.

**Changed**
- **Colours:** only white (near-black in dark mode), greys and the **logo blue** (`#02A3F6`; a slightly deeper `#0288D1` for buttons and blue text in light mode, so it stays readable on white). The purple of the default theme is gone. The *check* amber is darker in light mode.
- **Editor solve list:** every solve is a **compact card with a grey border** (blue border and a light blue tint when selected): the number and the time in bold, one grey line (`at 3:41 · clip 3:20–3:46 · with scramble`), and **Edit** (grey), **View** (blue, was *Gallery*) and a **red delete button with a bin icon**, side by side. The *Checked* button is now a small outlined **✓ It's fine** under the check lines.
- **Footage tab:** tapping a video **opens it in the editor straight away**; the options (show its clips, export clips, rename, forget, reconnect, trash) moved to a round **⋮ button** at the bottom right of its picture (and at the end of a list row). A video that can't be opened still shows its options when tapped, so it can be reconnected.

### Phase 3b: the editor finds solves, the Gallery exports them; checks

**Added**
- **Choose and export clips in the Gallery.** **Long-press** a clip to start choosing: tap clips to tick them, **All / None** (for the clips shown), a switch that sets **the scramble for all selected clips** at once, **Export** (asks first; offers *Only the N new* when some were exported before; a progress window keeps the screen on), ✕ or Back to stop. Clips whose video is gone and solves with no clip can't be ticked (a message says why).
- **Watch while choosing:** the arrows in a clip's corner open the full-screen player; at the top right it has the **tick circle** and a **Scramble: on/off** button, and swiping keeps the selection.
- **Export clips...** in a video's ⋮ options on the Footage tab ticks that video's clips that aren't exported yet.
- **Exported file names start with the video's title** and use the Gallery's solve number, for example `Sunday practice_Solve_03_11.263s.mp4`, so clips from different videos never clash (single exports from the player too).
- **Delete straight from a solve's row** (no warning), with an **Undo** bar under the Prev/Next bar for about 8 seconds. The row slides away and the solve that moves into its place is selected (the playhead jumps to it).
- **Timing checks** (`checkCuts`): solves **too close** to each other (the second one gets no clip) and solves that would have **started before the previous one stopped** are flagged *check*, **both solves of the pair**, in the editor and the Gallery.
- **Only show solves to check** switch above the list (Prev / Next then step through those only), and an **Only clips that need a check** box in the Gallery's Filter.
- **Mark a check as looked at** (database version 6, `cuts.reviewed`): **It's fine** on a card, **Mark all N as checked** (with a confirmation), and *It's fine: mark as checked* in the player's details. A solve with no clip stays flagged until one of the pair is deleted or moved. With the check filter on, the card slides away and the next solve to check is selected.

**Changed**
- **The editor is for finding the real solves only.** The export switch and the *Include scramble* tick box are gone from the rows, the review bar has no switch, and there is **no export button** in the editor. Solves that were switched off by hand (or by the scan for a reason other than "left over from before the video") are now normal solves; only *left over* cuts stay out.
- **"Include the scramble in all clips"** moved from the pinned area into the Setup box (**3. Scramble**), next to the buffers. *(Moved again in the testing phase, see above.)*

### Phase 3a: re-scans keep your edits

**Added**
- **Find solves on a video that already has saved cuts merges instead of replacing** (`RescanMerge.kt`, database version 5, `cuts.user_edited`). A found solve and a saved cut are the same solve when their **solve parts** (timer start to stop, worked out from the time) overlap; the scramble isn't used for matching.
  - Cuts you **changed** (time, position, switches, scramble, notes, added by hand, marked as checked) or **exported** stay exactly as they are.
  - Cuts you never touched take the new scan's values.
  - New solves are added.
  - Untouched cuts the new scan didn't find again are kept and get the note *not found again by the last Find solves - check it*.
- The status line says what happened: "Re-scan done in 95s: 2 new, 1 updated, 12 kept as you left them, 1 not found again (check them)". The Find solves window explains this instead of warning that edits are lost.
- On upgrade, older cuts that clearly show a change (a note, added by hand, switched off, scramble different from the video's setting) are counted as changed.

### Phase 2j: the last viewer fixes (Phase 2 is finished and tested on the phone)

**Fixed**
- **The black flash when a swipe settles on a clip** (found frame by frame in a 120 fps screen recording: exactly one black frame in the video area, just before playback). Cause: when a page became the playing one, the viewer threw its still picture away and drew a new one, which had to load again, so for one frame there was nothing to show. Now every page keeps **one still picture for its whole life** (on top of the player; it fades out once the player has drawn its first picture and is never removed), and `Thumb` draws a picture that is already in memory **in the very first frame** instead of after a load (`ThumbCache.peek`).
- While a clip's full-size still is still being made for the first time, the viewer shows the grid's small picture of that clip instead of black (`placeholderKey` in `Thumb`).

**Changed**
- **The *Export only* tag is now the same green as *Exported*** (it was yellow-green). The yellow-green colour is gone.

### Phase 2i: smoother start of a clip

**Fixed**
- **The picture shown before a clip starts playing is now the clip's own first picture** (it used to be the frame where the final time first shows, which is later in the clip), so when the video takes over there is no jump. The still also waits 0.15 s after the player's first picture before fading, so the player's picture has reached the screen.
- **The tag for a clip whose raw video is gone is now *Export only*** (yellow-green here; green since Phase 2j), which is shorter and says what the clip is.

### Phase 2h: swiping between clips

**Fixed**
- **Black flashes while holding a swipe near the middle:** the playing clip now only changes once a swipe has come to rest. Before, wobbling around the halfway point switched the clip back and forth and restarted the player each time. During a swipe the current clip keeps playing and the next one shows its still picture.
- **The clip tag for a clip whose raw video isn't available is now *Raw unavailable*** (yellow-green), whether the video was deleted or is in the trash.

### Phase 2g: flicker and tag colours

**Fixed**
- **The black flash between the still picture and the playing clip:** the still now covers the new clip from the very first frame of the page (before, it was one frame late, which showed black), and the still pictures of the neighbouring clips are prepared ahead of time.
- **Tag colours:** *Raw deleted* / *Raw in trash* on a clip is now yellow-green (the clip still works); the Footage tab's tags are back to *In trash* and *Video deleted* (red).

### Phase 2f: more viewer fixes

**Fixed**
- **No more black flash when a clip starts playing after its still picture:** the still stays in place until the player has really drawn its first picture and then fades away, and the player no longer paints black over it while starting.
- **The controls no longer hide right after a skip.** Playback pauses and restarts by itself during a skip, and that was counted as "the clip started playing".
- **The tag for a clip whose raw video is gone now reads *Raw deleted*** (or *Raw in trash*) instead of *No video*.

### Phase 2e: viewer and Gallery polish

**Added**
- **Swipe left or right** on the Gallery to move between the Clips and Footage tabs (tapping a tab still works).
- **Pinch animation:** the grid grows or shrinks under your fingers while you pinch, and the pictures glide to their new places when the number of columns changes (Clips and Footage).

**Changed**
- **Player controls stay for 4 seconds after the last tap or skip** (they still hide the moment a clip starts playing, and no longer hide right after a skip).
- **The skip seconds are changed in the player only** (the *Skip* button above the seek bar); the Settings window no longer has it.
- **Swiping between clips:** the picture of the next clip is now shown whole and in the same place as the video (it was cropped to the whole screen), is sharper, and the clip's own still stays in place until the player draws its first picture, so there is no black or mismatched flash.
- **The trash button for an exported clip is now a small trash icon at the very bottom of the clip's sheet.**

**Fixed**
- **A video moved to the trash from CubeCut is now treated as deleted straight away** (only its exported clips stay in the Gallery, and it can't be opened in the editor). Before, Android still let CubeCut open it, so everything carried on as if it were there. *Recently deleted* has an *Already restored* button for items you brought back in another app.

### Phase 2c: the full-screen clip viewer

**Added**
- **The viewer fills the screen and plays as soon as a clip is chosen.** The controls are drawn by CubeCut itself, as icons: skip back, play or pause, skip forward (with the seconds shown inside the arrows) and a seek bar. They are hidden while the clip plays, a tap on the picture shows them, and they come back when the clip ends. They no longer flash when swiping to another clip.
- **Skip buttons jump by 5 seconds** (they were 15). The number can be changed right in the player (the small *Skip 5s* button above the seek bar) or in **Settings**: 1, 2, 3, 5, 10, 15 or 30 seconds.
- **Swipe left and right** to move through the clips in the order the Gallery shows them (or through one session's solves when opened from the editor).
- **Swipe up anywhere** on the picture (a short swipe is enough), or tap the handle, for the details sheet: the solve time and where it is in the video, the **Include the scramble** switch, **Export this clip**, the note with its Clear and social media buttons, the *From this video* card, and *Open in editor at this solve*. **Swipe down** anywhere on the picture or on the sheet (when its contents are scrolled to the top) to close it, again with a short swipe. A first-time arrow floating up the picture says "Swipe up anywhere for more"; Settings can show it again.
- **The scramble switch** changes the saved cut immediately, so the editor shows the same choice, and the viewer re-plays the clip with its new start. The usual rules apply (a clip's scramble starts where the previous cut ended, whether or not that cut is on). A solve that is switched off in the editor can still be viewed and exported from here.
- **Export this clip** exports just that solve with the same maths as the editor's export, records it as exported, and asks first if it was already exported (a second file would be made).
- **The timer setup is remembered per video** (database version 3): where in the video it was set up, the four corners and the typed number. Opening that session again sets the timer up from them automatically, so Find solves is ready. The editor now says whether the setup was remembered, brought back, or why it couldn't be. If it can't be rebuilt, nothing is lost and the timer is set up again by hand.
- **Move to trash and restore** (database version 4; Android 11 or newer). *Move video to trash* in a session's Footage options, and *Move the exported clip to trash* on a clip's sheet, each with a warning first and Android's own confirmation after. Nothing is deleted permanently by CubeCut. **Recently deleted** (the \u22EE menu) lists what was moved to the trash, with a *Restore* button for each; Android deletes trashed items for good after about 30 days. A trashed video behaves like a deleted one (only its exported clips stay in the Gallery) and everything returns when it is restored. Only videos picked from the file picker's Videos or Images lists (and the clips CubeCut exported) can be moved to trash this way.

**Changed**
- The old clip detail screen is replaced by the viewer.

### Phase 2b: testing fixes and new Gallery features

**Added**
- **Exported tracking.** Every export now records which solves were written and where the files are (database version 2; an existing database is upgraded without losing anything). Clips show an **Exported** tag; the Filter can show only exported or only not-exported clips.
- **When the original video is deleted,** the Gallery keeps only the clips that were exported (they play from the exported file, tagged *Video deleted*); the not-exported ones disappear from the Gallery (the saved cuts stay in the database, so importing the video again brings everything back). While the video exists, every found clip shows, exported or not.
- **Sort by up to three things at once** (Sort window: *Sort by*, *Then by*, *Then by*): date (by calendar day, so one day's sessions tie), solve time fastest or slowest, session name, order in the video, needs check first, exported first, not exported first. Example: Date, then Fastest = each day's solves from fastest to slowest.
- **Filter window:** choose which raw videos' clips are shown (tick or untick each video, All / None) and whether to show all, exported only or not exported only clips. The Footage tab can also hide or show one video's clips.
- **Pinch to resize the grid** (two fingers, 2 to 6 columns, remembered separately for Clips and Footage). Small cells show less text.
- **Notes with social media buttons** on the clip detail screen: a note box plus TikTok, Instagram, YouTube, Facebook, X and Reddit buttons. Pressing a button writes the platform's name into the note (pressing it again removes it). The platforms in a note also show on the clip's card and list row.
- A message under the tabs when something fails (for example when forgetting a session).
- **Reconnect video:** a session whose video is missing gets a *Reconnect video* button (in its Footage options and on its clip pages). Pick the video and it is attached to the saved session again, without leaving the Gallery. Needed when Android gives a restored or moved video a new link. The length must match.
- **Gallery refreshes itself** every time you come back to the app (for example after deleting or restoring a video in another app), and there is a **Refresh** button.
- **Clear button** on a clip's note.
- **Gallery button on every solve row in the editor:** saves your edits, returns to the Gallery and opens that solve's page.
- **Clip page player:** the controls are hidden from the start, show for 2 seconds after a tap, and hide the moment playback starts.

**Fixed**
- Sorting or filtering now **scrolls the list back to the top**.
- Opening a clip in the editor now **scrolls the list to the selected solve**.
- **Renaming the video file no longer creates a second, empty session.** A video is now recognised first by its link (which doesn't change when a file is renamed), then by file name, size and length, then by exact size and length.
- **"Opened your saved session"** now stays visible in the editor's header instead of being overwritten.
- **Forget session** now deletes the session, its cuts and its export records in one step and reports a problem if it fails.

### Phase 2: the Gallery

**Added**
- **The Gallery is the first screen** (`Gallery.kt`). The editor opens from it and has a **Gallery** button (and the back button) to return; the latest edits are saved before it closes.
- **Clips tab:** every solve of every saved session as a **grid of pictures** (the frame where the timer first shows the final time) with the **solve time at the upper right**, or as a **list** with the clip title (`Session title \u00B7 Solve 03 \u00B7 11.263s`), date and status. Badges: *Check* (the scan left a note), *Left out*, *No video*.
- **Footage tab:** the raw session videos as a grid or list: picture, title, date filmed, length, number of solves on, best time, how many need a check. Tap one for: show its clips, open in editor, **rename**, **forget**.
- **Sort** (remembered between runs). Clips: newest / oldest session, name A-Z, fastest, slowest, needs check first. Footage: newest, oldest, name, most solves, best time, longest video. Grid or list is remembered separately for each tab.
- **Clip detail screen:** plays just that clip (the same start and end the export would use), shows the time, the solve number and where it is in the video, the status and note, and a **From this video** card (picture, title, date, length, file name; tap it to see all that session's clips). **Open in editor at this solve** jumps straight to it.
- **Pictures are made on demand** (`Thumbnails.kt`): the first time a clip or session is shown, a frame is taken from the video and kept as a small JPEG in the app's cache folder (and in memory while the app runs). Nothing is made during Find solves. If Android clears the cache they are made again; without the video they show as grey.
- **"No video" handling:** if a session's video can't be opened any more, its clips, times and notes stay visible, playback and *Open in editor* are disabled, and a note explains that importing the video again reconnects it.
- The author credit line and About window are now shared (`AuthorCreditLine`, `AboutDialog`) and also appear at the bottom of the Gallery.

**Changed**
- The temporary *Sessions* window of Phase 1 is gone; the Footage tab replaces it.
- Opening a session in the editor does not remember the timer setup yet, so Find solves on an old session needs the timer set up again.

### Phase 1: saved sessions

### Added
- **Saved sessions.** Every imported video gets a small record on the phone (`SessionStore.kt`, plain Android SQLite, no new libraries): where the video is, its name, size and length, when it was filmed, the buffer settings, and one row per cut. No video or pictures are stored, only numbers and text, so even a thousand solves are a few dozen KB.
- **Automatic saving.** Every change to the cuts, the buffers or the scramble switch is saved about 0.4 s after it happens, and the date of the last Find solves is kept.
- **Opening a saved session.** Importing a video the app has seen before (same file name, size and length) brings back its saved cuts and settings, so the review and export work again without running Find solves. The timer only has to be set up again if you want to run Find solves again.
- **Forgetting a session** removes only CubeCut's record; the video and exported clips are untouched.
- **Source missing:** if a saved session's video was moved or deleted, a message says so; importing the video again re-links it to the saved cuts.

### Changed
- **Import video now uses the system file picker** instead of the photo picker, because Android lets the app keep its permission to read a video chosen that way (`takePersistableUriPermission`). The photo picker's link stops working after a restart, which would make saved sessions unopenable.
- The Find solves warning now says the saved list for this video is replaced.

### Notes
- Written without a compiler and not yet run on a phone; test steps are in the pull request / commit notes.
- Android limits how many videos an app can hold permission for (128 on older versions, 512 on newer ones). "Forget this session" releases the permission.
- Still to come in later phases: review statuses and filters, merge on re-scan, saving the timer setup per session, multi-select, favourites, tags, backup.

## [2.0.2] - 2026-11-03

A clean-up and bug-fix release on top of 2.0.0. How solves are found, how digits are read and how clips are calculated did **not** change.

### Changed
- Limited OpenCV to only arm64 devices resulting in lower storage requirement.

### Fixed
- Fixing a bug with exported videos where audio is not synced when using some video editors.

### Licence, author credit and documentation (2026-11-06)
Added later the same version, with no version bump. How solves are found, how digits are read and how clips are calculated did **not** change.

**Added**
- **GPL-3.0 licence** (`LICENSE`): anyone may use and modify CubeCut, but shared versions must stay open source under the same licence and keep the copyright notice.
- **Additional licence terms** (`NOTICE.md`, GPL section 7): the author credit must stay in the app's About window, and changed versions must be marked as changed. Using the app and the exported clips is not restricted.
- **Author credit in the app:** a small line "CubeCut by DadCuber" at the bottom of the main screen. Tapping it opens an **About window** with the credit, where to find the author, a one-line description and the licence notice (`AUTHOR_NAME`, `AUTHOR_FIND_ME` and the `showAbout` dialog in `MainActivity.kt`).
- `add_license_headers.py`: adds the licence header to every `.kt` file (run once from the project root).

**Documentation**
- **README:** new "Tested setup" section listing what has actually been tried (Samsung Galaxy S25 Ultra only; 1080p at 30 and 60 fps; fixed tripod; QiYi Bluetooth Timer LCD and csTimer's "Digital 2" font with the small-decimals option off) and what has not (other phones, head-mounted or moving cameras, other fonts, 4K / HDR). Also: the arm64-only requirement from 2.0.2, a "Things to know" list (heat, storage, originals untouched), a "Support" section (shared as-is, no tech support), and the licence section. Version line brought up to 2.0.2.
- **Project guide:** version, tested phone / footage / timer font (sections 0, 1, 7), the repository file list (3.3, 3.5), installation needs (4.4), the status section (9) and the restart prompt (10.2) updated to 2.0.2.

## [2.0.1] - 2026-11-03

A clean-up and bug-fix release on top of 2.0.0. How solves are found, how digits are read and how clips are calculated did **not** change.

### Changed
- **Find solves shows one progress display.** The card in the scrolling area (percentage, thick bar, "you can switch to another app") is kept and now has the **Cancel** button. The thin second progress bar with its own Cancel, under the Find solves button, is gone.
- **The Setup box folds away as soon as Find solves starts**, instead of when it finishes. It still opens again if the scan finds no solves, so the timer can be set up again.
- The solves list looks up each row's clip by cut number instead of searching the clip list for every row.

### Fixed
- **Importing a video during a scan** is no longer possible. Before, the scan carried on with the old video and, when it finished, filled the new video's list with the old video's solves. *Import video* is also disabled while an export runs.
- **Set up timer, Set up again and Read timer here are disabled during a scan.** They share non-thread-safe OpenCV objects with the scan and could crash it.
- **The scan stops when its coroutine is cancelled**, not only when Cancel is pressed. Before, a scan whose screen had been torn down kept running to the end with its decoder open.
- **Export of clips with a very large keyframe** (high-bitrate or 4K footage) failed because of a fixed 4 MB buffer. The buffer now doubles when a sample does not fit, up to 64 MB. *(Relies on `readSampleData` throwing `IllegalArgumentException` for a sample that is too large; not yet verified on a device.)*

### Performance and memory
- The clips are recomputed only when the cuts, the buffers or the video length change, not about 30 times a second while the video plays.
- `ShapeFinder` and `TimerEngine` got a `release()`. The shape finder used during setup is freed right after use, and the old timer engine is freed when a new setup replaces it or a new video is imported.
- `TimerVision.prep` always releases its temporary images, even when something fails part-way. `bitmapToGray` recycles the bitmap copy it makes.
- The scan's frame source (`CodecSource`) no longer asks the second extractor for the keyframe position when the next frame is just ahead of the decoder (the answer would always be "keep decoding").
- All exported clips reuse one sample buffer instead of allocating 4 MB per clip.
- The remembered row positions of the solves list are cleared whenever the list is cleared or a cut is deleted.

### Removed
- Unused code: the `LazyColumn`, `LazyRow` and `itemsIndexed` imports; `TimerStore.save()` and `TimerStore.load()` (the timer setup has not been saved since 1.1.0; `TimerStore.clear()` stays to delete files left by 1.x); the unreadable-stretch tracking in the scan (`UnreadableSpan`, `UNREADABLE_MIN_SEC`, `ScanResult.unreadable`, `ScanResult.framesRead`), which only fed the "check these parts" list removed in 2.0.0; `CutItem.manual` and `FoundSolve.digits`, `votes` and `reads`, which were never read.

### Notes
- Written without a compiler and not yet run on a phone at the time of writing (the app has since been built and run on the developer's phone); see section 9 of the project guide for what is still unconfirmed.
- Documentation updated: project guide (sections 0, 2.1, 2.1b, 2.3, 2.5, 2.6, 2.7, 3, 5, 7, 8, 9, 10, 11), README.
- Version bump still to do in `app/build.gradle.kts`: `versionName "2.0.1"` and a higher `versionCode`.

## [2.0.0] - date not recorded (developed on the `dev` branch)

The "first-frame cuts" version. The app no longer listens to sound at all.

### Added
- **Cuts come from the timer, not from sound.** The app reads the timer along the whole video. A non-zero time that stays on the display (the same time on at least two reads, nothing clearly different in between) is a finished solve, and the **first frame that shows that time** is the cut. The exact digits are decided by a vote over a few more reads, with a bias that counts a 4 or 7 double against its "1" look-alike (the reader's two known mistakes).
- **First-frame search**: walk back in 0.5 s steps to the nearest frame that showed something else, then a binary search down to one frame (1/60 s). Unreadable frames are skipped; a single odd reading is forgiven.
- **Solve-only clips.** Per solve you choose *solve only* (from the time on the timer plus a **before buffer** before the first frame) or *with scramble* (from where the previous cut ended). Every clip ends an **after buffer** after the first frame. Both buffers are sliders in the Setup box (default 2.5 s).
- **Two scramble controls:** a tick box per row and one pinned "Include scramble in all clips" switch (shows on / off / mixed).
- **Boundary rule:** the scramble of a clip starts at the end of the previous cut whether or not that cut is switched on, so a solve you left out is never dragged into the next clip. *Delete cut* removes a cut from the maths; a cut that the scan switched off because its time was already on the timer when the video began counts as deleted.
- **Cancel** for Find solves, and a status line with the time taken ("Done in 27s: 22 solves found, 1 to check").
- **Fast scan.** Find solves takes its pictures from one running `MediaCodec` decoder (`FrameSource.kt`) instead of asking `MediaMetadataRetriever` for every frame, with a startup check against the old picture (which also settles the rotation) and an automatic fall-back to the old way if anything is off. The test session went from about **107 s to about 27 s**. Also a wider step while the display shows zero (2 s), and "last position first" in the timer engine.
- **Review improvements:** *Prev / Next* scroll the solves list to the selected row; the Edit window moves the cut point in 0.5 s and 0.1 s steps and jumps to that frame; **+ Cut at playhead** snaps to the first frame that shows the time (up to 30 s back); a reminder after every scan to check that every solve was captured.
- Setup guidance: a bold instruction to set the timer up on a frame that shows an actual time after a solve, **not 0.000**, and a warning when a setup is saved from an all-zero number.
- Python prototype `pc_prototype/first_frame_scan_prototype.py` (scan, clip maths, noisy-timer simulator and tests) and the record `docs/FIRST_FRAME_CUTS_CHANGES.md`.

### Changed
- The screen was reorganised so the solves list gets as much room as possible: only the header, video, timeline, Prev/Next bar, the two main buttons and the all-clips switch stay pinned; the Setup box, progress, status and list scroll together; Export sits at the end of the list. Video height went from 30 % to 27 % of the screen.
- The timer setup is now the first thing on the screen (one *Set up timer* button inside the Setup box) because the whole scan depends on it.
- The Find solves confirmation estimates `0.3 × video length` (it was 6 s per sound).
- The timeline shows one marker per cut, on the first frame that shows the time, plus a strip that draws what each exported clip contains (dim = scramble, bright = solve with buffers).

### Removed
- All sound analysis (audio decoding, waveform, spike detection) and the **sensitivity slider**.
- The "check these parts of the video" list (replaced by the reminder after a scan) and the note "couldn't see exactly when this time appeared" (meaningless to a person).
- The second *Set up timer* button.
- Scanning the video in parallel pieces was built, measured (105 s with two workers against 107 s with one) and removed again.

## [1.1.0] - date not recorded

The timer-reading expansion (record: `docs/TIMER_READING_CHANGES.md`). The digit reading itself stayed as tuned for the QiYi timer.

### Added
- **csTimer and other seven-segment screens** (PC, phone, tablet), next to Stackmat-style LCD timers such as the QiYi Smart Timer.
- **The reader seam** (`DisplayReaders.kt`): the part that turns a straightened picture into digits sits behind a `DisplayReader` interface, so a reader for other fonts can be added later without touching the rest. `GlyphReader` is a disabled placeholder for it.
- **Fixed-camera mode** for plain screens that give the shape finder nothing to lock onto (the camera must stay still).
- **Snug read box** around the digits after learning, kept only if the typed number is still reproduced.
- **Variable-length numbers** (8 digit slots): one setup reads `9.87`, `12.34` and `1:02.34` (type `102.34` for 1:02.34 in the setup screen).
- **Setup continues with a warning** when the typed number cannot be reproduced exactly, instead of stopping.
- **A new full-screen setup** (`TimerSetupScreen.kt`): the whole frame in both steps, pinch zoom, two-finger pan, a **Fit** button, auto-zoom on the tapped display and a round magnifier while a corner dot is dragged.

### Changed
- Digits are found by column height (short shapes such as icons, text, the decimal point and a colon are ignored) and the row band is trimmed to the rows the digits cover, which fixed setups on csTimer screens with a camera icon in the box.
- **The timer setup is no longer saved.** It lasts until the app closes or another video is imported, so it is set up at every app start and every video import (the camera and window position change between sessions). Files left by 1.0.0 are deleted at start-up.

## [1.0.0] - date not recorded

The first version with the full phone app and the UI-polish milestone. Cuts were found by **sound** (a loud sound marked a candidate stop) and **confirmed by reading the timer**. Runs offline, with no AI and no internet permission.

### Added
- Import a session video with the system photo picker (no storage permission), a film strip and a progress window while it is read.
- **Find solves**: spikes in the sound, each checked by reading the QiYi-style timer (found by shape with ORB features and RANSAC, digits read by 7-segment classification, majority vote over about 27 frames); a **sensitivity** slider (default 4.0 on the phone) and a clip-length setting.
- **Set up timer**: mark the display's four corners and type the number it shows, so other seven-segment timers can work too; the setup was saved on the phone.
- A **review screen**: switch each solve on or off, edit its time, nudge its cut point, add a missed cut by hand, with a timeline of markers and a red playhead.
- **Lossless export** (no re-encoding) to `Movies/CubeCut`, one file per solve named with its time (`Solve_03_11.263s.mp4`); a solve with no known time exports as `unknown` and can be given a time with *Add time*.
- Polish: logo and launcher icon; collapsible Setup box; pinned video and timeline with a separately scrolling list; a *Prev / Next* bar; Export at the end of the list; a warning window before Find solves; keyword highlighting of notes that need a look; an "export finished" window; pinch-to-zoom and double-tap-to-reset on the video (a TextureView from an XML layout); Find solves keeps running while you use another app (foreground service with a progress notification and a "finished" notification; Android 13+ notification permission asked once); the screen stays awake during long jobs.
- A Python prototype of the sound method, `pc_prototype/cubecut.py`, and `generic_reader_prototype.py`, which mirrors the Kotlin digit reader.
