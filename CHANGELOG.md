## 0.1.0

Initial release. Android only.

- `start()` returns a `StartResult` that distinguishes the reasons Android
  refuses a foreground service start, rather than throwing or failing silently.
- Native buffering of fixes recorded while no Dart isolate is alive, drained
  through `drainBuffered()`.
- `sessionState` persisted natively and returned in
  `StartResult.recoveredSessionState` after the OS restarts a killed service.
- Partial wakelock held for the session, so Doze does not suspend the isolate
  while the foreground service is up.
- Helpers for battery saver state and the vendor auto-start settings screens.
