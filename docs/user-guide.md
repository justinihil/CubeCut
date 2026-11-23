# Using CubeCut

How to cut a session (version 3.x), what to expect, and what to try when something goes wrong. For what has been tested and what hasn't, see the [README](../README.md#tested-setup).

## Cutting a session

1. **Import** the video with *Import video* in the Gallery. If CubeCut has seen the video before, it says *Imported before*. **Open saved** brings back its saved solves, settings and timer setup; **Cancel** leaves things as they were. A new video asks **Which puzzle is this?** One puzzle per video works best; *Decide later* skips it (set it later in the Setup box).
2. **Set up the timer.** Everything depends on this step.
   1. Pause on a frame that shows **an actual time after a solve**: the timer has stopped and shows its final time. **Do not use 0.000.**
   2. Tap the display, drag the four dots onto its corners (a magnifier helps), and type the number shown. For 1:02.34, type `102.34`.
   3. The setup is remembered for this video.
3. **Find solves.** The app reads the timer along the whole video. This took about 30 s for a 22-solve session on the test phone; it is slower on slower phones or longer videos. You can switch apps (a notification shows progress) or press Cancel. While it runs, importing and timer setup are blocked.
4. **Review** the list. The arrows step through solves. Tapping the timeline selects the solve there; playing the video doesn't change the selection.
   - **Missed solve:** put the playhead on the first frame that shows its final time, tap **Add a missed solve at the playhead**, and type the time. The list jumps to the new solve, which blinks briefly.
   - **Wrong time or cut point:** use **Edit**. It shows the frame the cut is on, zoomed onto the timer, and ◂ 0.1s / 0.1s ▸ buttons move the cut. Tap **Whole frame** to see all of it.
   - **Not a solve:** delete it. The next solve is selected, and the Undo bar says which one (*now on Solve 7*). Undo is available for 8 s. An exported solve asks whether to keep its file.
   - **Flagged *check*:** *Only show solves to check* narrows the list. **It's fine** clears a flag and moves to the next solve to check. While the filter is on, solves you fix stay in the list, marked **✓ Fixed** or **✓ Checked**, until you switch it off.
5. **Choose what each clip contains:** with the scramble, or the solve only, plus the **before** and **after** buffers (2.5 s each by default, in the Setup box). The switch above the list sets the scramble for every clip, and each card's scramble button changes one clip. One clip can have its own buffers: **Edit** → *Buffers for this clip* → *Custom* (or *Buffers* in the viewer's details). Its card then says *own buffers*.
6. **Export** from the Gallery. Long-press a clip, drag over others to tick them, then tap **Export**. Files go to `Movies/CubeCut` and are named like `Sunday practice_Solve_03_11.263s.mp4`, or `Sunday practice_3x3 OH_Solve_03_11.263s.mp4` for a tagged clip (a *Normal* type is left out). Clips exported before are pointed out, so you don't make a second copy by accident.

If the timer can't be read, nothing is blocked. You can always add solves by hand, even before Find solves or without a timer setup.

## Adding a single solve

1. Tap *Import video* in the Gallery and pick a video of **one** solve. Choose **One solve** (*A session* opens the editor as above).
2. It opens in the player straight away. Pause on the stopped timer and type its time in the bar at the top, then *Save*.
3. Swipe up for the details: penalty, puzzle, note and *Shared on*, as for any clip.
4. **Trim** is optional (**Edit** in the details). Off: the whole file is the clip and nothing needs exporting. On: drag the two handles; the clip is exported as a copy. End it just after the timer stops: the end is the solve's stop.
5. Single solves are in the **Clips** tab only, not under Footage. **Delete video** and **Forget this solve** are at the bottom of the details.

Picking the same video again says *Already in your Gallery*. The editor's own *Import video* is for sessions only.

## Things to know

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
- **Penalty (+2 / DNF):** set it in the editor's *Edit* window or in the viewer's details. Tap *+2* again for each extra +2; *No penalty* clears it. Times show as `13.263 (+2)` or `DNF(11.263)`, and sorting and *best* count the penalty (a DNF sorts last). The solve-only clip still starts from the time on the timer.
- **An exported clip is a copy.** Moving its cut or fixing its time later doesn't change the file; export it again. A **penalty renames** the file to match (`…_13.263s_plus2.mp4`, `…_DNF_11.263s.mp4`). After a reinstall, Android asks first. Changing an exported clip's **buffers** in the viewer asks *Re-export now?*; after the new file is made you can move the old one to the trash (or keep both).
- **Typing times:** in the Edit window, use a colon for a minute or more (`1:02.34`). A plain `102.34` there means 102.34 s. Only the timer-setup box reads `102.34` as 1:02.34.
- **Back up your Gallery:** *⋮ → Backup and restore → Back up now* saves one small file (no videos) where you choose. Keep a copy off the phone. Uninstalling still clears the Gallery; exported files in `Movies/CubeCut` stay.
- **Restore** (new phone or after a reinstall): copy your videos over, then *Restore from a backup*. Videos already in the Gallery stay as they are. Then tap **Reconnect videos** and pick all the videos at once, and **Reconnect exported clips** and pick the files in `Movies/CubeCut`. Importing a video again also reconnects it.
- Swiping CubeCut out of recent apps stops Find solves. Some phones' battery savers stop it too: set CubeCut's battery use to *Unrestricted*.

## Troubleshooting

| Symptom | Try |
|---|---|
| Setup says it couldn't match the digits | Pause on a sharp frame with hands away. Type exactly what the display shows, including the decimal point. Place the dots on the four corners |
| "Timer saved WITH A WARNING" | Use *Read timer here* on a few frames after solves. If readings are wrong, check the typed number and the dots, or set up again on a sharper frame |
| "Couldn't find digit shapes in this box…" | Check how many digits you typed. Drag the dots tighter around the digits only, leaving out icons and window edges |
| "Couldn't recognise the timer's shape" | Pick a sharper frame with the whole timer body visible |
| Setup mentions fixed-camera mode, and later reads fail | The display is a plain screen, so the camera and the window must not move. Set up again if they did |
| No solves found | Set up again on a frame showing a real final time (not 0.000). Test with *Read timer here* on moments after solves. Or add solves by hand |
| A real solve is missing | Its time was on screen for under about 1.5 s, or it was hidden by a hand or glare. Add it at the playhead |
| One digit wrong (e.g. `12.112` for `12.412`) | Fix it with **Edit**. If it happens often, set up again on a clearer frame |
| csTimer reads wrong digits | Turn off csTimer's "use small font after decimal point" |
| A solve-only clip contains the whole scramble | The solve has no time yet. Add the time |
| Find solves is very slow | The fast decoder may have been rejected. Logcat tag `CubeCut` shows a `Frame source:` line with the reason. Long videos are simply slow |
| No notification during Find solves | Allow notifications: Settings → Apps → CubeCut. Find solves works without them |
| Buttons are greyed out | Find solves or an export is running. Wait, or press Cancel |
| Exported clips aren't in the phone's gallery | Look in *Files → Movies → CubeCut* |

## Reporting a problem

CubeCut is shared as-is (see [README](../README.md#support)). If you do report something, include:
- the exact error text, or the `FATAL EXCEPTION` block from Logcat;
- a screenshot (the black-and-white picture under *Read timer here* helps a lot);
- for a wrong time, the expected vs actual time and its timestamp in the video;
- if possible, a 60–90 s excerpt cut without re-encoding: `ffmpeg -ss 00:12:00 -i session.mp4 -t 90 -c copy excerpt.mp4`.
