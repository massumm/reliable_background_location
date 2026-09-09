# Example

A minimal tracker that demonstrates the three things worth demonstrating:
requesting permissions in the order Android accepts, reporting a refused start
honestly, and recovering fixes buffered across process death.

## Running it

```bash
cd example
flutter run
```

A physical device is required. Doze, battery saver and low-memory kills do not
reproduce on an emulator.

## What to look at

**`_ensurePermissions`** — calls `requestPermissions()` and then decides what to
do with the answer. Note that a denied *background* permission is a warning
rather than a failure: tracking still starts, it just pauses once the app leaves
the screen. That distinction is the difference between `canStart` and
`isComplete`.

**`_restoreAfterProcessDeath`** — drains the native buffer *before* subscribing
to the live stream, then re-attaches only if the service is actually running.
The green banner reports how many fixes were recovered.

**`_start`** — checks `result.started` before showing any recording UI. Showing
"recording" over a service that never came up is the failure this package
exists to prevent, and the example is the wrong place to model it badly.

## Trying the interesting case

With tracking running, from another terminal:

```bash
adb shell am kill dev.masumalreza.reliable_background_location_example
```

Walk for a few minutes, then reopen the app. The green banner should report the
fixes collected while the process was dead.

See [doc/testing.md](../doc/testing.md) for the rest.
