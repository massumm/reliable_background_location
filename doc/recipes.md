# Recipes

Working patterns for the cases this package was built for. Each one is complete
enough to paste.

## 1. A run tracker that survives being killed

The order matters: drain first, then listen. A fix from the live stream can
land before the drain completes, so sort by `timestamp` rather than trusting
arrival order.

```dart
class RunTracker {
  final _route = <LocationSample>[];
  StreamSubscription<LocationSample>? _sub;

  Future<void> resume() async {
    // Whatever the service recorded while this isolate was dead.
    final missed = await ReliableBackgroundLocation.drainBuffered();
    _route.addAll(missed);

    if (await ReliableBackgroundLocation.isRunning()) {
      _listen();
    }
  }

  Future<bool> start() async {
    final granted = await ReliableBackgroundLocation.requestPermissions();
    if (!granted.canStart) return false;

    final result = await ReliableBackgroundLocation.start(
      notification: const NotificationConfig(
        title: 'Recording your run',
        text: 'Tap to return to the app',
      ),
      location: const LocationConfig(interval: Duration(seconds: 3)),
      sessionState: {'runId': const Uuid().v4()},
    );

    if (!result.started) return false;

    _listen();
    return true;
  }

  void _listen() {
    _sub ??= ReliableBackgroundLocation.locations.listen((s) {
      _route.add(s);
      _route.sort((a, b) => a.timestamp.compareTo(b.timestamp));
    });
  }
}
```

## 2. Resuming an interrupted session

`sessionState` is persisted natively, so it comes back even when the OS killed
your process and restarted the service on its own.

```dart
final result = await ReliableBackgroundLocation.start(
  notification: notification,
  sessionState: {
    'runId': runId,
    'startedAt': DateTime.now().toIso8601String(),
  },
);

final previous = result.recoveredSessionState;
if (previous != null) {
  // The OS interrupted a session that was still going. Continue it rather
  // than starting a new one, or the user loses the first half of their run.
  final buffered = await ReliableBackgroundLocation.drainBuffered();
  await repository.appendToRun(previous['runId']!, buffered);
}
```

## 3. Live stats in the notification

Android rate-limits notification updates. Once every few seconds is plenty;
updating on every fix costs battery for no visible gain.

```dart
var metres = 0.0;
LocationSample? last;

ReliableBackgroundLocation.locations.listen((s) {
  if (last != null) metres += distanceBetween(last!, s);
  last = s;

  if (_route.length % 10 == 0) {
    ReliableBackgroundLocation.updateNotification(
      text: '${(metres / 1000).toStringAsFixed(2)} km',
    );
  }
});
```

## 4. Handling every failure honestly

The point of a typed `StartResult` is that each cause needs a different
response from your UI.

```dart
final result = await ReliableBackgroundLocation.start(notification: n);
if (result.started) return;

switch (result.failure!) {
  case StartFailure.locationPermissionMissing:
    await ReliableBackgroundLocation.requestPermissions();

  case StartFailure.notificationPermissionMissing:
    showDialog(/* explain that the notification is not optional */);

  case StartFailure.backgroundLocationPermissionMissing:
    // Cannot be granted from a prompt on Android 11+.
    await ReliableBackgroundLocation.openBatteryOptimisationSettings();

  case StartFailure.notAllowedFromBackground:
    // Something started tracking off-screen — a push handler or a timer.
    // Move the call to a user interaction.
    log('background start refused: ${result.message}');

  case StartFailure.locationServicesDisabled:
    showDialog(/* ask the user to switch location on */);

  case StartFailure.unknown:
    Sentry.captureMessage('start failed: ${result.message}');
}
```

## 5. Warning the user before they lose data

Two states are worth surfacing *before* a long session, not after it.

```dart
final state = await ReliableBackgroundLocation.checkPermissions();
if (state.canStart && !state.isComplete) {
  // Tracking will run, then stop the moment the app leaves the screen.
  // This is the state behind almost every "it stopped by itself" report.
  showBanner(
    'Choose "Allow all the time" or tracking will pause when you '
    'switch apps.',
    action: ReliableBackgroundLocation.openAutoStartSettings,
  );
}

if (await ReliableBackgroundLocation.isBatterySaverOn()) {
  showBanner(
    'Battery saver is on — fixes will be less frequent.',
    action: ReliableBackgroundLocation.openBatteryOptimisationSettings,
  );
}
```

## 6. Filtering the fixes worth keeping

The first fixes after a cold start are routinely accurate to 50 m or worse, and
a mock provider needs no root to enable.

```dart
ReliableBackgroundLocation.locations
    .where((s) => !s.isMocked)
    .where((s) => (s.accuracy ?? 999) < 30)
    .listen(addToRoute);
```

Keep the rejected fixes if you are drawing a live map — dropping them silently
makes the dot freeze with no explanation. Show a "poor GPS signal" state
instead.

## 7. Checking state when the app resumes

The service may have been stopped by the system while you were away, and
`isRunning()` is the only honest answer.

```dart
class _Page extends State<Page> with WidgetsBindingObserver {
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) async {
    if (state != AppLifecycleState.resumed) return;

    final missed = await ReliableBackgroundLocation.drainBuffered();
    if (missed.isNotEmpty) route.addAll(missed);

    final running = await ReliableBackgroundLocation.isRunning();
    if (!running && expectedToBeRunning) {
      // Killed and not restarted. Tell the user rather than pretending.
      showBanner('Tracking stopped. Tap to resume.');
    }
  }
}
```
