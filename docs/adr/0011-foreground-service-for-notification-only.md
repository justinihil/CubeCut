# ADR-0011: Foreground service only for the notification; work stays in the screen

- **Status:** Accepted (documented retroactively)
- **Date:** 1.0.0; service type changed in the 3.0.0 review fixes

## Context
Find solves takes from about 30 s to several minutes, and users switch apps while it runs.

## Decision
- The scan runs in `EditorScreen`'s coroutine scope (`runFindSolves`).
- `AnalysisService` does no work. It only shows the progress notification, so Android keeps the process alive.
- The service type is `mediaProcessing` on Android 15+ and `dataSync` below. `onTimeout` stops the service.
- The framework `startForeground(id, notification, type)` is called directly, because `ServiceCompat` wasn't available in the project's AndroidX version at the time.

## Consequences
- This was the simplest design that survives switching apps.
- Swiping the app away stops the scan.
- Some vendors' battery savers kill the scan anyway; set CubeCut's battery use to Unrestricted.
- Export doesn't use the service at all.
- Moving the work into a service or ViewModel would fix these, and was noted as "a bigger change".
