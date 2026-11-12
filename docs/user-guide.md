# Using CubeCut

How to cut a session (version 3.x), what to expect, and what to try when something goes wrong. For what has been tested and what hasn't, see the [README](../README.md#tested-setup).

## Cutting a session

1. **Import** the video with *Import video* in the Gallery. If CubeCut has seen the video before, its saved solves, settings and timer setup come back.
2. **Set up the timer.** Everything depends on this step.
   1. Pause on a frame that shows **an actual time after a solve**: the timer has stopped and shows its final time. **Do not use 0.000.**
   2. Tap the display, drag the four dots onto its corners (a magnifier helps), and type the number shown. For 1:02.34, type `102.34`.
   3. The setup is remembered for this video.
3. **Find solves.** The app reads the timer along the whole video. This took about 30 s for a 22-solve session on the test phone; it is slower on slower phones or longer videos. You can switch apps (a notification shows progress) or press Cancel. While it runs, importing and timer setup are blocked.
4. **Review** the list. The arrows step through solves. Tapping the timeline selects the solve there; playing the video doesn't change the selection.
   - **Missed solve:** put the playhead on the first frame that shows its final time, tap **Add a missed solve at the playhead**, and type the time.
   - **Wrong time or cut point:** use **Edit**. It shows the frame the cut is on, and ◂ 0.1s / 0.1s ▸ buttons move the cut.
   - **Not a solve:** delete it. Undo is available for 8 s. An exported solve asks whether to keep its file.
   - **Flagged *check*:** *Only show solves to check* narrows the list. **It's fine** clears a flag.
5. **Choose what each clip contains:** with the scramble, or the solve only, plus the **before** and **after** buffers (2.5 s each by default, in the Setup box). The switch above the list sets the scramble for every clip, and each card's scramble button changes one clip.
6. **Export** from the Gallery. Long-press a clip, drag over others to tick them, then tap **Export**. Files go to `Movies/CubeCut` and are named like `Sunday practice_Solve_03_11.263s.mp4`. Clips exported before are pointed out, so you don't make a second copy by accident.

If the timer can't be read, nothing is blocked. You can always add solves by hand.

## Things to know

- **Long videos warm the phone** and use battery during Find solves. Plug it in for a long session.
- **Exports are full-quality copies** made without re-encoding, so a session can take several GB. They may start up to about 1 s early, because a clip must begin on a keyframe.
- **Your original video is never changed.** CubeCut only reads it.
- **Re-running Find solves keeps your edits.** Solves you changed or exported stay as they are, untouched ones update, and new ones are added. A solve you deleted can come back if the scan finds it again.
- **Delete** moves a video or clip to the phone's trash after a warning. You can restore it for about 30 days from *Recently deleted*. CubeCut never deletes anything permanently. Only videos picked from the picker's Videos or Images lists can be trashed from CubeCut.
- If you delete a raw video, its exported clips stay in the Gallery (*Export only*). Clips that were never exported disappear, but their solves stay saved, and importing the video again brings them back.
- **An exported clip is a copy.** Moving its cut or fixing its time later doesn't change the file; export it again.
- **Typing times:** in the Edit window, use a colon for a minute or more (`1:02.34`). A plain `102.34` there means 102.34 s. Only the timer-setup box reads `102.34` as 1:02.34.
- **CubeCut's saved list is not backed up** and doesn't move to a new phone. Uninstalling clears the Gallery, though exported files in `Movies/CubeCut` stay.
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
