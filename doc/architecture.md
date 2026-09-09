# Architecture

Four collaborators, split by **how long each one has to stay alive** rather than
by what it does. That split is the whole design: every hard bug in Android
background location comes from code assuming it will outlive something that
disappears first.

```
              ┌──────────────────────────────────────────────┐
              │  PLAY SERVICES        (outside your app)     │
              │  PendingIntent registered ─────────────┐     │
              └────────────────────────────────────────┼─────┘
                                                       │ every N seconds
          ┌────────────────────────────────────────────┼───────────┐
          │  YOUR APP PROCESS                          ▼           │
          │                                                        │
          │   LocationUpdatesReceiver ──► is an isolate listening? │
          │            │                        │                  │
          │            │                 yes    │    no            │
          │            │                        │     └──► LocationBuffer
          │            │                        ▼                (disk)
          │            │                 EventChannel ──► Dart stream
          │            │                                           │
          │   TrackingService  (foreground service + wakelock)     │
          │            │                                           │
          │            └──► ServiceState  (SharedPreferences)      │
          └────────────────────────────────────────────────────────┘
```

Three lifetimes, longest first:

| Layer | Outlives | Therefore |
| --- | --- | --- |
| Play Services registration | your whole app | fixes keep coming after you die |
| Service, buffer, state | the Dart isolate | must not touch Flutter types |
| Dart isolate | nothing | may be gone at any moment |

The package's only job is keeping data from falling between those layers.

## Why a PendingIntent, not a callback

Play Services accepts location requests two ways:

```kotlin
requestLocationUpdates(request, callback)      // a Kotlin object in your process
requestLocationUpdates(request, pendingIntent) // registered inside Play Services
```

A callback is an object on your heap. When Android kills the process, it goes
with it, and delivery stops silently. A `PendingIntent` lives in the Play
Services process, so delivery survives — Android will even start your process
back up purely to hand you the broadcast.

That is the foundation. Everything else exists to make use of the fixes that
keep arriving after you are gone.

## LocationUpdatesReceiver

Runs in a process that may have been created seconds ago solely to deliver one
broadcast, where `Application` may not have finished initialising and no Flutter
engine exists. It may therefore depend on **nothing but `Context` and disk**.

Its only decision, made per fix rather than per broadcast:

```kotlin
val sink = LocationBridge.activeSink
if (sink != null) sink(toEventMap(location))
else LocationBuffer.append(context, location, maxSamples)
```

Per fix, because a batch delivered during Doze can straddle the moment the app
is resumed — some of those fixes belong on disk and some belong on the stream.

## LocationBuffer

One JSON object per line, appended. Appending a fix costs one short write rather
than re-serialising everything collected so far, which matters when the process
may be killed mid-write.

`drain()` reads and clears in one locked step. A partial read that left the file
in place would hand Dart the same fixes twice, and duplicate points in a route
are worse than missing ones — they look like the user teleported.

A corrupt final line (a write interrupted by a kill) is skipped rather than
allowed to discard the rest.

## ServiceState

Everything needed to rebuild the service without Dart's help: notification copy,
sampling configuration, whether a session was active, and the caller's own
`sessionState`.

This exists because of one specific Android behaviour:

```kotlin
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // intent == null means the OS restarted us on its own, hours later,
    // with no isolate alive to ask what was going on.
    if (intent == null && !ServiceState.isRunActive(this)) {
        stopSelf()          // the user had already stopped; do not resume
        return START_NOT_STICKY
    }
    ...
}
```

Distinguishing "resurrect an interrupted session" from "restart something the
user already stopped" is only possible because the answer is on disk.

## TrackingService

Holds the foreground service, and an **untimed** partial wakelock:

```kotlin
pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).acquire()
```

A timed acquire renewed on each fix looks safer and is worse. Miss one fix
during Doze and the lock expires, which suspends the isolate, which means no
further fix arrives to renew it. The failure sustains itself and looks like a
random device-specific bug.

It also classifies `startForeground` refusals by exception **class name**:

```kotlin
name == "ForegroundServiceStartNotAllowedException" -> "notAllowedFromBackground"
```

By name because that class only exists from API 31; referencing it directly
would not compile against a lower `compileSdk`, and catching only the base type
would lose the distinction the caller needs.

## LocationBridge

Exists only while a Flutter engine does. Anything that must outlive the engine
lives in `ServiceState`; anything that must run without one lives in the
receiver.

Its least obvious job is not answering `start()` too early:

```
startForegroundService()  returns immediately
        │
        │  ... startForeground() has not been attempted yet ...
        ▼
answering "started: true" here would always be a lie
```

So the bridge polls `TrackingService.isForegroundActive` every 100 ms for up to
five seconds, and reports **failure** if the outcome is still unknown — never an
optimistic success. Silent success over a dead service is the exact bug this
package exists to prevent.
