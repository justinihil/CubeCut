# Using CubeCut

How to cut a session (version 3.x), what to expect, and what to try when something goes wrong. For what has been tested and what hasn't, see the [README](../README.md#tested-setup).

## Cutting a session

1. **Import** one or more videos with *Import video* in the Gallery. Each new video goes to the **Sessions** tab, and CubeCut starts *Find solves* on it by itself, one video after another. If you pick **one** video CubeCut has seen before, it says *Imported before*: **Open saved** opens it in the editor, **Cancel** leaves things as they were. When you pick several, the ones seen before are skipped and counted. Set the puzzle later, in the editor's Setup box or the video's ⋮ menu.
2. **The timer** needs nothing: CubeCut finds it by itself, csTimer and other timers with solid digits as well as QiYi-like displays. A timer on the mat may slide or be hit. Keep it in view and readable, also while it runs, for most of the video. **Film with a fixed camera:** a small bump is fine, but if the camera ends up pointing somewhere else, the solves after that can be missed. If two numbers run at the same time, it asks which one is the timer.

   **If it finds no timer,** add the solves at the playhead (step 4). There is no setup by hand any more (until 4.0 there was one, as a backup).
3. **Find solves** reads the timer along the whole video. This took about 30 s for a 22-solve session on the test phone; it is slower on slower phones or longer videos. It runs in the background: you can leave the editor, use the Gallery, open another video or use other apps meanwhile (a notification shows progress), or press Cancel. While it runs, this video's list can't be changed. Several videos wait their turn.
   - The **slim bar above the tabs** says how far it is. Tap it to see every video: *Waiting*, *Reading the timer 40%*, or how it ended. **Choose** answers *Which one is your timer?*; **Add by hand** opens a video whose timer wasn't found; **Open** opens one to review. *Clear finished* empties the list.
   - A card that says **Timer not found** opens in the editor: add its solves at the playhead. **Choose the timer** means it asks which number is the timer.
   - To scan a video again, open it and press *Find solves again*.
4. **Review** the list. The arrows step through solves. Tapping the timeline selects the solve there; playing the video doesn't change the selection.
   - **Missed solve:** put the playhead on the first frame that shows its final time, tap **Add a missed solve at the playhead**, and type the time. The list jumps to the new solve, which blinks briefly.
   - **? Maybe a missed solve** (dashed card): the timer ran without a final time, was reset, or couldn't be read for long. **Go there** moves the playhead; find the first frame of the final time and add the solve, and the hint goes. **Nothing here** drops it for good (Undo for 8 s). A solve missed without any of these signs gets no hint.
   - **Wrong time or cut point:** use **Edit**. It shows the frame the cut is on, zoomed onto the timer, and buttons move the cut: ◂ 0.5s / 0.1s / 0.1s ▸ / 0.5s ▸ for big moves, then **◂ 1 frame / 1 frame ▸** to land on the exact first frame of the final time. Tap **Whole frame** to see all of it.
   - **Not a solve:** delete it. The next solve is selected, and the Undo bar says which one (*now on Solve 7*). Undo is available for 8 s. An exported solve asks whether to keep its file.
   - **Flagged *check*:** *Only show solves to check* narrows the list. **It's fine** clears a flag and moves to the next solve to check. While the filter is on, solves you fix stay in the list, marked **✓ Fixed** or **✓ Checked**, until you switch it off.
5. **Choose what each clip contains:** with the scramble, or the solve only, plus **extra time** before and after: seconds kept before the solve starts and after the timer stops (2.5 s each by default, in the Setup box). The switch above the list sets the scramble for every clip, and each card's scramble button changes one clip. One clip can have its own extra time: **Edit** → *Extra time for this clip* → *Custom* (or *Extra time* in the viewer's details). Its card then says *own extra time*.
6. **Export** from the Gallery. Long-press a clip, drag over others to tick them, then tap **Export**. Files go to `Movies/CubeCut` and are named like `Sunday practice_Solve_03_11.263s.mp4`, or `Sunday practice_3x3 OH_Solve_03_11.263s.mp4` for a tagged clip (a *Normal* type is left out). Clips exported before are pointed out, so you don't make a second copy by accident.

If the timer can't be read, nothing is blocked. You can always add solves by hand, even before Find solves.

## Videos of one solve

A video of one solve (a competition attempt, for example) is imported like any other. CubeCut finds its solve and cuts it like any solve, so its clip is exported as a copy, and a penalty renames that copy. To save space, delete the original afterwards (*Delete video*: the clip keeps playing from the copy). If its timer isn't found, add the solve by hand in the editor.

The **Sessions** tab's switch shows **All**, **Sessions** or **Single solves** (every video with one solve). The Clips tab's *Filter* switches single solves on or off together.

**Single solves made before 4.0** (imported as *One solve*) keep working as they are:
- tapping one opens it in the player; its time is typed in the bar at the top;
- **Trim** is optional (**Edit** in the details). Off: the whole file is the clip and nothing needs exporting. On: drag the two handles; the clip is exported as a copy;
- **Delete video** and **Forget this solve** are at the bottom of its details;
- picking its video again says *Already in your Gallery*.

## Things to know

- **Several sessions at once:** on the Sessions tab, long-press a card, then tap others to tick them. **Delete videos** moves their original videos to the phone's trash (exported clips stay and keep playing; one confirmation for all), **Forget** removes CubeCut's records of them (the files stay). Each warns first. A video being scanned can't be ticked.
- **The last copy of a solve:** when a clip's original video is deleted, its exported clip still shows and plays. Deleting that file warns in red that it is the last copy; it goes to the phone's trash for about 30 days.

- **New clips** (blue *New* badge) are clips CubeCut found that you haven't opened or exported yet: handy after importing a batch. Opening a clip in the viewer or exporting it clears its badge; *⋮ → Mark all as seen* clears them all. *Filter → Only new clips* shows just those.

- **Long videos warm the phone** and use battery during Find solves. Plug it in for a long session.
- **Exports are full-quality copies** made without re-encoding, so a session can take several GB. They may start up to about 1 s early, because a clip must begin on a keyframe.
- **Your original video is never changed.** CubeCut only reads it.
- **Re-running Find solves keeps your edits.** Solves you changed or exported stay as they are, untouched ones update, and new ones are added. A solve you deleted can come back if the scan finds it again.
- **Delete** moves a video or clip to the phone's trash after a warning. You can restore it for about 30 days from *Recently deleted*. CubeCut never deletes anything permanently. Only videos picked from the picker's Videos or Images lists can be trashed from CubeCut.
- **Delete many exported files at once:** hold a clip to start ticking, tick the clips, then tap the red bin next to *Export*. Only their files go to the trash; the solves and notes stay. Clips that only had their file left (*Export only*, *Solve deleted*) leave the Gallery. Export skips those clips.
- **Puzzle tags:** a video has a puzzle and a type (Normal, OH, BLD, Relay, PLL attack, or *Other* with your own name). Its clips follow it.
  - Set it at import, in the editor's Setup box, or with *Set puzzle for this video…* in a video's ⋮ menu.
  - Mixed footage: in the editor, untick the card of each solve that isn't that puzzle. It is then *not tagged*.
  - Tag single clips in the Gallery: *Set puzzle…* while ticking, or *Change* next to *Puzzle* in the viewer's details. With every clip of a video ticked, it tags the video instead.
  - *Filter* shows only one puzzle or type, or only clips that are not tagged.
- **Shared on:** in the viewer's details, tap the platforms you posted a clip to (TikTok, Instagram, YouTube…). It's separate from the note. *Filter* → *Shared on* shows only clips shared on one platform, or those *Not shared yet*.
- **Scramble for many clips:** while ticking, the switch under the header turns the scramble on or off for every ticked clip. *All* ticks every clip shown. With every clip of a video ticked, it works like the editor's *Scramble in all clips*.
- If you delete a raw video, its exported clips stay in the Gallery (*Export only*). Clips that were never exported disappear, but their solves stay saved, and importing the video again brings them back.
- **Watching clips:** tap the picture for the controls. Double-tap the left or right half to skip back or forward; keep tapping to skip further. **Replay** (next to *Skip*) loops every clip until you switch it off.
- **The viewer's details** (swipe up) are in groups, top to bottom: the time and anything that needs a look first (*Reconnect video*, *check*); then *Export* / *Editor* / *Delete file* and whether it's exported; **Solve** (puzzle with its logo, penalty); **Clip** (scramble, extra time); **Note and sharing**; and the video it comes from.
- **Penalty (+2 / DNF):** set it in the editor's *Edit* window or in the viewer's details. Tap *+2* again for each extra +2; *No penalty* clears it. Times show as `13.263 (+2)` or `DNF(11.263)`, and sorting and *best* count the penalty (a DNF sorts last). The solve-only clip still starts from the time on the timer.
- **An exported clip is a copy.** Moving its cut or fixing its time later doesn't change the file; export it again. A **penalty renames** the file to match (`…_13.263s_plus2.mp4`, `…_DNF_11.263s.mp4`). After a reinstall, Android asks first. Changing an exported clip's **extra time** in the viewer asks *Re-export now?*; after the new file is made you can move the old one to the trash (or keep both).
- **Typing times:** in the Edit window, use a colon for a minute or more (`1:02.34`). A plain `102.34` there means 102.34 s.
- **Back up your Gallery:** *⋮ → Backup and restore → Back up now* saves one small file (no videos) where you choose. Keep a copy off the phone. Uninstalling still clears the Gallery; exported files in `Movies/CubeCut` stay.
- **Restore** (new phone or after a reinstall): copy your videos over, then *Restore from a backup*. Videos already in the Gallery stay as they are. Then tap **Reconnect videos** and pick all the videos at once, and **Reconnect exported clips** and pick the files in `Movies/CubeCut`. Importing a video again also reconnects it.
- Find solves goes on in the background. Some phones' battery savers stop it: set CubeCut's battery use to *Unrestricted*.

## Troubleshooting

| Symptom | Try |
|---|---|
| "Couldn't find the timer" | Check that the number is in view and readable **while it runs**, with a decimal point. A timer that hides its running time, or shows whole seconds, is found by its final times: at least three different ones must be in the video. Then add the solves at the playhead. Very small digits (under about 24 pixels tall), strong blur and a screen turned far over don't read. In csTimer, the fonts "digital 2" and "digital 3" mostly aren't read: use the default font or "digital 1", "digital 4" or "digital 5" |
| A solve says *the running time doesn't confirm this time* | The time may be misread, or it was a test tap. Check it, then **Edit** or delete it |
| A solve says *the timer was hidden when it stopped* | The cut is worked out and can be a few frames off. Move it in **Edit** |
| No solves found | The final time has to stay in view for a moment. Add solves at the playhead |
| A real solve is missing | Its time was on screen for under about 1.5 s, or it was hidden by a hand or glare. Add it at the playhead |
| One digit wrong (e.g. `12.112` for `12.412`) | Fix it with **Edit** |
| The wrong timer is used (csTimer and a QiYi in view) | csTimer always goes first. Cover it, or keep it out of the picture |
| A solve-only clip contains the whole scramble | The solve has no time yet. Add the time |
| Find solves is very slow | The fast decoder may have been rejected. Logcat tag `CubeCut` shows a `Frame source:` line with the reason. Long videos are simply slow |
| No notification during Find solves | Allow notifications: Settings → Apps → CubeCut. Find solves works without them |
| Buttons are greyed out | Find solves or an export is running. Wait, or press Cancel |
| Exported clips aren't in the phone's gallery | Look in *Files → Movies → CubeCut* |

## Reporting a problem

CubeCut is shared as-is (see [README](../README.md#support)). If you do report something, include:
- the exact error text, or the `FATAL EXCEPTION` block from Logcat;
- a screenshot of the editor after *Find solves*;
- for a wrong time, the expected vs actual time and its timestamp in the video;
- if possible, a 60–90 s excerpt cut without re-encoding: `ffmpeg -ss 00:12:00 -i session.mp4 -t 90 -c copy excerpt.mp4`.
