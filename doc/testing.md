# Testing background location

None of the behaviour this package exists for reproduces on an emulator. Doze,
battery saver and low-memory process kills all need a physical device.

Run the example app, then work through these.

## 1. Live tracking

Grant permissions, press Start, walk. Fixes should appear at roughly the
configured interval.

If nothing arrives: check `isRunning()`, then `lastStartError()`.

## 2. Screen off — is the wakelock working?

```
Start tracking → lock the screen → walk 10 minutes → unlock
```

The track should be continuous. A gap that begins a minute or two after the
lock is the signature of a suspended isolate: the foreground service is alive
and the notification is showing, but nothing is being delivered to Dart.

## 3. Process death — the one that matters

```bash
# Force-kill the process without touching the service registration.
# This is the closest simulation of a real low-memory kill.
adb shell am kill dev.masumalreza.reliable_background_location_example
```

Then walk for a few minutes and reopen the app. `drainBuffered()` should return
the fixes recorded while the process was dead.

Note that `am kill` only works on a backgrounded app, and is *not* the same as
`am force-stop`, which also cancels the `PendingIntent` registration — that is
what happens when a user hits "Force stop" in settings, and nothing can recover
from it.

## 4. Sticky restart

```bash
adb shell am kill <package>            # process dies, service is restarted by the OS
adb shell dumpsys activity services | grep -A5 TrackingService
```

The service should reappear. `StartResult.recoveredSessionState` should come
back populated on the next `start()`.

## 5. Doze

```bash
adb shell dumpsys deviceidle force-idle     # enter Doze immediately
adb shell dumpsys deviceidle unforce        # leave it
```

Delivery will slow dramatically. That is correct behaviour, not a bug — the
test is that it resumes rather than stopping permanently.

## 6. Battery saver

Switch it on in system settings. `isBatterySaverOn()` should return true, and
the interval should stretch.

## 7. Permission revoked mid-session

Start tracking, then revoke location in system settings. Android kills the app.
On relaunch, `start()` should report `locationPermissionMissing` rather than
appearing to succeed.

## Useful commands

```bash
# Is the service actually in the foreground?
adb shell dumpsys activity services | grep -i trackingservice

# What is holding a wakelock?
adb shell dumpsys power | grep -i wake

# Watch the plugin's own logs
adb logcat -s RBL.Service RBL.Bridge RBL.Receiver RBL.Buffer

# Current location request registrations
adb shell dumpsys location | grep -A10 "Location Requests"
```

## What good looks like

| Test | Pass |
| --- | --- |
| Screen off, 10 min | no gap in the track |
| `am kill`, 3 min, reopen | `drainBuffered()` returns those minutes |
| Sticky restart | service reappears, session state recovered |
| Doze forced | delivery slows, then resumes |
| Permission revoked | `locationPermissionMissing`, not a silent success |
