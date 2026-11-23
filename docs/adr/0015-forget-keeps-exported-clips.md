# ADR-0015: Forgetting a session keeps its exported clips in the Gallery

- **Status:** Accepted
- **Date:** 2026-11-23

## Context
Up to 3.0.1, *Forget this session* deleted the footage row together with its cuts and export records. The exported files stayed in `Movies/CubeCut` ([ADR-0008](0008-never-delete-permanently.md)), but CubeCut no longer showed them. Deleting the raw video, by contrast, keeps its exported clips in the Gallery as *Export only*. The owner found this inconsistent: clips they had made disappeared from the app although the files were still on the phone.

## Decision
When a session with export records is forgotten, its footage row is kept and marked `forgotten_ms` (DB v7), with its cuts and export records. A forgotten session:
- is hidden from the Footage tab and Recently deleted (videos);
- shows its exported clips in Clips as *Export only*, keeping their *Solve N* titles and notes;
- is never matched on import, so importing the video again **starts from zero** (owner's choice) and can't be reconnected;
- is deleted for good by `loadGallery` once none of its exported files opens and none is in the trash.

A session with no export records is still deleted at once.

## Consequences
- The 3.1 release has a schema change (v6 → v7), so the later roadmap versions move up by one.
- The same video can have a forgotten session and a new one. Their clips show side by side, and exporting a solve in the new session makes a second file.
- Code that lists sessions must choose: `GalleryData.sessions` (Footage tab) or `GalleryData.footage` (everything, needed for the Clips filters and `ui.hidden`).

## Alternatives considered
- **Offer to trash the files when forgetting:** no schema change, but kept files would still vanish from the app.
- **Ask each time** (keep in the app or trash): a longer dialog, and the schema change anyway.
- **Bring the session back on re-import** ("forget" = hide): no duplicate files, but contradicts "Can't be undone", and the owner preferred starting from zero.
- **Keep the behaviour, clearer wording:** doesn't address the problem.
