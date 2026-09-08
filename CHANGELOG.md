## 0.1.0

Initial release. Android only.

- `start()` returns a `StartResult` that distinguishes the reasons Android
  refuses a foreground service start, rather than throwing or failing silently.
- `requestPermissions()` prompts in the order Android accepts — foreground
  location and notifications first, then background location as a separate
  request, since from Android 11 the two cannot share one prompt.
- `PermissionState` separates `canStart` from `isComplete`, so a caller can
  tell "cannot track" from "will track until the app leaves the screen".
- Native buffering of fixes recorded while no Dart isolate is alive, drained
  through `drainBuffered()`.
- `sessionState` persisted natively and returned in
  `StartResult.recoveredSessionState` after the OS restarts a killed service.
- Partial wakelock held for the session, so Doze does not suspend the isolate
  while the foreground service is up.
- Helpers for battery saver state and the vendor auto-start settings screens.

Verified on a physical device: live tracking, screen-off continuity, recovery
of fixes buffered across process death, and `START_STICKY` service restart.
