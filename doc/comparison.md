# How this compares

Written in September 2026. Check the current state before trusting any of it —
this is a space where a package can go from fine to broken with one Android
release.

| Package | Last release | Cost | Scope |
| --- | --- | --- | --- |
| `flutter_background_geolocation` | actively maintained | **paid licence for Android release builds** | iOS + Android, geofencing, motion detection |
| `geolocator` | actively maintained | free | position API; no foreground service |
| `flutter_foreground_task` | actively maintained | free | generic foreground tasks, not location |
| `background_locator_2` | Mar 2023 | free | location, but predates Android 14 service types |
| `background_location` | Sep 2025 | free | location |
| `flutter_background_service` | Dec 2024 | free | generic background service |
| **`reliable_background_location`** | **new** | free (MIT) | Android location only |

## Where each one leaves you

**`flutter_background_geolocation`** is the most capable thing in this space by
a distance — iOS as well as Android, geofencing, motion-based throttling, years
of device-specific workarounds. If it fits your budget, use it. The catch is
that Android release builds need a paid licence, which rules it out for
side projects, early-stage products, and teams billing in currencies where the
price is a real number.

**`geolocator`** is the right default for foreground position, and does not
claim otherwise. It gives you no foreground service, so background tracking
stops when the OS decides it should.

**`flutter_foreground_task`** keeps a foreground service alive well, and is
maintained. But it is generic: location buffering, wakelock policy, and
telling apart the reasons a service refused to start are still yours to solve.
It has also historically broken across Android releases that changed foreground
service rules — worth checking against your `targetSdk` before committing.

**`background_locator_2`** last shipped in March 2023, before Android 14
required `foregroundServiceType` to match a permission. On a modern `targetSdk`
it will not start.

## What this package does differently

Two things, and they are narrow on purpose.

**It reports why a start failed instead of throwing or lying.**

```dart
final result = await ReliableBackgroundLocation.start(notification: n);
if (!result.started) {
  print(result.failure);  // notAllowedFromBackground, locationPermissionMissing, ...
}
```

Android refuses foreground service starts for several unrelated reasons and
signals it by throwing. Wrap that in a try/catch that returns void and the
caller cannot distinguish "the user denied notifications" from "you called this
from a background isolate" — and worse, a UI saying *recording* over a service
that never came up.

**It keeps fixes that arrive after your process is dead.**

```dart
final missed = await ReliableBackgroundLocation.drainBuffered();
```

Play Services keeps delivering to a registered `PendingIntent` after Android
kills your app. Those fixes are written to disk by a receiver that depends on
nothing but `Context`, and returned on the next launch. This is what turns a
track with a hole in it into a continuous one.

## What it deliberately does not do

- **iOS.** Not implemented. `start()` throws `UnsupportedError`.
- **Geofencing, activity recognition, motion throttling.** Out of scope.
- **Guaranteed sampling rate.** `interval` is a request; Doze and battery saver
  stretch it and there is no API that changes that.
- **Beating vendor ROMs.** Xiaomi, Oppo, Vivo and others kill background
  services on their own schedule. `openAutoStartSettings()` gets the user to
  the relevant screen; nothing can do more than that from inside an app.

If you need iOS or geofencing, this is the wrong package and
`flutter_background_geolocation` is the right one.
