# reliable_background_location

Background location tracking on Android that keeps recording when the screen
goes off, when Doze kicks in, when battery saver is on, and after the OS has
killed your process.

[![pub package](https://img.shields.io/pub/v/reliable_background_location.svg)](https://pub.dev/packages/reliable_background_location)

## The problem

If you have shipped a Flutter app that records a route — a running app, a
delivery tracker, a field-survey tool — you have probably met some of these:

- The service dies on Android 14+ with
  `ForegroundServiceStartNotAllowedException`, and the same code worked fine
  before you bumped `targetSdk`.
- The route draws normally, then stops two minutes after the screen locks.
- The user completes a 10 km run and the app has 3 km of it, because Android
  killed the process at kilometre four.
- It works on your Pixel and not on the tester's Xiaomi.

None of these are bugs in your Dart code. They are four separate Android
platform behaviours, and each one needs its own fix.

## Why this is hard

**A foreground service alone does not keep your Dart isolate running.** This
is the one that catches everyone. The service survives, the notification stays
up, and Doze suspends the isolate anyway — so `onLocationChanged` fires into a
void. A partial wakelock is required on top of the foreground service, and it
has to be held for the life of the session rather than renewed on each fix.

**`startForeground()` can be refused, and refusal is fatal.** It throws rather
than returning an error, and when it throws the service is killed — not merely
left without a notification. A plugin that swallows that exception hands you a
silent failure: your UI says "recording", nothing is recorded. Causes are
unrelated to each other — revoked location permission, `POST_NOTIFICATIONS`
denied on Android 13+, or a background start on Android 12+ — so they need
telling apart. This package returns them as
[`StartFailure`](https://pub.dev/documentation/reliable_background_location/latest/reliable_background_location/StartFailure.html)
values instead of throwing.

**`START_STICKY` restarts the service without restarting Dart.** Android may
resurrect a killed service hours later, with no isolate alive to hear from it.
Any session bookkeeping living only in Dart is gone at that point, so it has to
be persisted natively. Pass `sessionState` to `start()` and it comes back in
`StartResult.recoveredSessionState` on the next launch.

**Fixes arriving with no isolate to receive them are lost unless buffered.**
Play Services keeps delivering to a `PendingIntent` after your process dies.
Those fixes are buffered natively here and retrievable with `drainBuffered()` —
which is what turns a track with a hole in it into a continuous one.

**Vendor ROMs ignore the AOSP rules.** Xiaomi, Oppo, Vivo, Huawei and others
kill background services regardless, and hide the opt-out in a different place
on each skin. `openAutoStartSettings()` sends the user to the right screen where
one exists.

## Quick start

```dart
final result = await ReliableBackgroundLocation.start(
  notification: const NotificationConfig(
    title: 'Recording your run',
    text: 'Tap to return to the app',
  ),
  location: const LocationConfig(interval: Duration(seconds: 5)),
);

if (!result.started) {
  // Tracking is NOT running. Do not show a "recording" UI.
  switch (result.failure!) {
    case StartFailure.notificationPermissionMissing:
      // ask for POST_NOTIFICATIONS
    case StartFailure.backgroundLocationPermissionMissing:
      // send the user to app settings
    default:
      // log result.message
  }
  return;
}

ReliableBackgroundLocation.locations.listen((sample) {
  addToRoute(sample.latitude, sample.longitude);
});
```

### Surviving process death

Drain first, then listen. Order matters: a fix from the live stream can land
before the drain completes, so sort by `LocationSample.timestamp` rather than
arrival order.

```dart
final missed = await ReliableBackgroundLocation.drainBuffered();
route.addAll(missed);

if (await ReliableBackgroundLocation.isRunning()) {
  ReliableBackgroundLocation.locations.listen(addToRoute);
}
```

### Manifest

The plugin already declares the service, the receiver, and the three
install-time permissions they need (`FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_LOCATION`, `WAKE_LOCK`) — the middle one has to match the
service type or Android 14+ refuses the start outright.

What it does **not** declare is the runtime permissions, because which ones you
ask for and when is a product decision that shows up in your Play listing. Add
these to your app's manifest:

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
```

This package does not request them for you — use `permission_handler` or your
own flow. `ACCESS_BACKGROUND_LOCATION` cannot be requested in the same prompt
as foreground location on Android 11+; the user has to grant it from system
settings.

## API

| Member | Purpose |
| --- | --- |
| `start(...)` | Start or reconfigure tracking. Returns a `StartResult` — check `started`. |
| `stop()` | Stop, dismiss the notification, release the wakelock. |
| `locations` | Live `Stream<LocationSample>`, for as long as an isolate is alive. |
| `drainBuffered()` | Return and clear fixes recorded while no isolate was listening. |
| `clearBuffered()` | Discard buffered fixes without reading them. |
| `isRunning()` | Whether the service is actually in the foreground and recording. |
| `lastStartError()` | Platform message from the last failed start. |
| `updateNotification(...)` | Rewrite the notification text without interrupting tracking. |
| `isBatterySaverOn()` | Whether battery saver is throttling delivery. |
| `openBatteryOptimisationSettings()` | Send the user to the exemption screen. |
| `openAutoStartSettings()` | Send the user to the vendor auto-start screen. |

## Behaviour by Android version

| Version | What changes |
| --- | --- |
| 10 (29) | `ACCESS_BACKGROUND_LOCATION` introduced. |
| 11 (30) | Background location must be granted from settings, not a prompt. |
| 12 (31) | Foreground services cannot be started from the background. |
| 13 (33) | `POST_NOTIFICATIONS` gates the service notification. |
| 14 (34) | `foregroundServiceType` must be declared and match the permission. |
| 15+ (35+) | Tighter limits on total foreground-service runtime. |

## Troubleshooting

**`StartFailure.locationPermissionMissing` on the very first start.** The
runtime permission has not been granted. A `location` foreground service type
is checked against `ACCESS_FINE_LOCATION` at `startForeground` time, so the
service cannot come up without it — request the permission before calling
`start()`. See `example/lib/main.dart` for the order Android requires.

**`StartFailure.notAllowedFromBackground`.** Something called `start()` while
the app was not in the foreground — a push handler or a timer, typically.
Start from a user interaction, or hold a foreground-service exemption.

**Tracking starts, then stops when the screen locks.** `ACCESS_BACKGROUND_LOCATION`
is missing. It cannot be requested in the same prompt as foreground location on
Android 11+; the user has to pick "Allow all the time" in system settings.

**Fixes arrive far less often than `interval`.** Check `isBatterySaverOn()`.
Battery saver and Doze both stretch delivery, sometimes to minutes, and there
is no API to opt out — only `openBatteryOptimisationSettings()` to ask the user.

**Everything works except on one manufacturer's phone.** Vendor ROMs kill
background services on their own schedule. `openAutoStartSettings()` opens the
relevant screen where one exists.

## Limitations

Stated plainly, because the alternative is a bug report:

- **Android only.** iOS has a different model (`allowsBackgroundLocationUpdates`,
  significant-location-change) and is not implemented. `start()` throws
  `UnsupportedError` there.
- **No guaranteed sampling rate.** `interval` is a request. Doze and battery
  saver will stretch it, sometimes to minutes.
- **No geofencing, no activity recognition, no map rendering.** This package
  does one thing.
- **Vendor kills cannot be fully prevented**, only made less likely by getting
  the user to the auto-start screen.

## License

MIT
