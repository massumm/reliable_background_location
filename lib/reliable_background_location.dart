/// Reliable background location tracking on Android.
///
/// Records a continuous position track that survives the screen turning off,
/// Doze, battery saver, and the app's own process being killed — the three
/// failure modes that make naive background location unusable in production.
///
/// See `ReliableBackgroundLocation` to get started.
library;

import 'dart:async';

import 'package:flutter/services.dart';

import 'src/config.dart';
import 'src/location_sample.dart';
import 'src/start_result.dart';

export 'src/config.dart';
export 'src/location_sample.dart';
export 'src/start_result.dart';

/// Entry point for background location tracking.
///
/// A single foreground service backs every method here, so calls are global
/// rather than per-instance. Starting twice updates the existing service
/// instead of creating a second one.
///
/// ## Minimum viable usage
///
/// ```dart
/// final result = await ReliableBackgroundLocation.start(
///   notification: const NotificationConfig(
///     title: 'Recording your run',
///     text: 'Tap to return to the app',
///   ),
/// );
///
/// if (!result.started) {
///   // Tracking is NOT running. Handle result.failure before showing any UI
///   // that implies recording has begun.
///   return;
/// }
///
/// final sub = ReliableBackgroundLocation.locations.listen(handleFix);
/// ```
///
/// ## Surviving process death
///
/// Android may kill the app and later restart the service by itself, with no
/// Dart isolate alive to receive callbacks. Fixes collected in that window are
/// buffered natively. On the next launch, drain them before trusting the
/// live stream:
///
/// ```dart
/// final missed = await ReliableBackgroundLocation.drainBuffered();
/// track.addAll(missed);
/// ```
///
/// Pass `sessionState` to [start] to have your own bookkeeping — a session id,
/// the distance so far — persisted alongside those samples and handed back in
/// [StartResult.recoveredSessionState].
///
/// ## Required manifest entries
///
/// The plugin contributes the service declaration, but the host app must
/// declare the permissions it actually wants:
///
/// ```xml
/// <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
/// <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION"/>
/// <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
/// <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
/// <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION"/>
/// <uses-permission android:name="android.permission.WAKE_LOCK"/>
/// ```
class ReliableBackgroundLocation {
  ReliableBackgroundLocation._();

  static const MethodChannel _channel = MethodChannel(
    'reliable_background_location/service',
  );

  static const EventChannel _events = EventChannel(
    'reliable_background_location/locations',
  );

  static Stream<LocationSample>? _locations;

  /// Live position fixes, for as long as a Dart isolate is alive to hear them.
  ///
  /// This stream says nothing about samples collected while the process was
  /// dead — use [drainBuffered] for those. The two are complementary: drain
  /// once at startup, then listen.
  static Stream<LocationSample> get locations {
    return _locations ??= _events
        .receiveBroadcastStream()
        .map((e) => LocationSample.fromMap(e as Map<dynamic, dynamic>))
        .asBroadcastStream();
  }

  /// Starts (or reconfigures) the tracking service.
  ///
  /// Always inspect [StartResult.started]. Android refuses foreground service
  /// starts for reasons that have nothing to do with your code — a revoked
  /// permission, a background start on Android 12+, notifications switched
  /// off — and reporting that honestly is the difference between a track with
  /// a gap and a track the user never knew was missing.
  ///
  /// Pass [sessionState] to persist your own key/value bookkeeping across
  /// process death; it comes back in [StartResult.recoveredSessionState].
  static Future<StartResult> start({
    required NotificationConfig notification,
    LocationConfig location = const LocationConfig(),
    Map<String, String>? sessionState,
  }) async {
    final map = await _channel.invokeMapMethod<String, Object?>('start', {
      'notification': notification.toMap(),
      'location': location.toMap(),
      'sessionState': sessionState,
    });
    return StartResult.fromMap(map ?? const {'started': false});
  }

  /// Rewrites the notification without interrupting tracking.
  ///
  /// Call this to show live progress. Android rate-limits notification
  /// updates, so once every few seconds is plenty; updating on every fix
  /// wastes battery for no visible gain.
  static Future<void> updateNotification({String? title, String? text}) {
    return _channel.invokeMethod('updateNotification', {
      'title': title,
      'text': text,
    });
  }

  /// Stops tracking, dismisses the notification, and releases the wakelock.
  ///
  /// Buffered samples are kept — drain them first if you still need them.
  static Future<void> stop() => _channel.invokeMethod('stop');

  /// Whether the service is currently in the foreground and recording.
  ///
  /// Worth polling when your app resumes: the service may have been stopped
  /// by the system while you were away, and this is the only honest answer.
  static Future<bool> isRunning() async {
    return await _channel.invokeMethod<bool>('isRunning') ?? false;
  }

  /// The platform message from the most recent failed start, if any.
  static Future<String?> lastStartError() {
    return _channel.invokeMethod<String>('lastStartError');
  }

  /// Returns and clears every sample buffered while no isolate was listening.
  ///
  /// Samples come back oldest first. Ordering against the live stream is on
  /// you: compare [LocationSample.timestamp] rather than arrival order,
  /// because a drain can complete after the first live fix has landed.
  static Future<List<LocationSample>> drainBuffered() async {
    final raw = await _channel.invokeListMethod<Map<dynamic, dynamic>>(
      'drainBuffered',
    );
    return (raw ?? const []).map(LocationSample.fromMap).toList();
  }

  /// Discards buffered samples without returning them.
  ///
  /// Use when the user abandons a session and the data is no longer wanted.
  static Future<void> clearBuffered() => _channel.invokeMethod('clearBuffered');

  /// Whether the device is in battery-saver mode.
  ///
  /// Battery saver tightens background limits well beyond ordinary Doze, and
  /// is the usual explanation for a track that thins out on one user's phone
  /// but not another's.
  static Future<bool> isBatterySaverOn() async {
    return await _channel.invokeMethod<bool>('isBatterySaverOn') ?? false;
  }

  /// Opens the system battery-optimisation screen for this app.
  ///
  /// There is no API to exempt yourself — the user has to do it. Explain why
  /// before sending them here, or they will simply back out.
  static Future<void> openBatteryOptimisationSettings() =>
      _channel.invokeMethod('openBatteryOptimisationSettings');

  /// Opens the vendor auto-start screen, where one exists.
  ///
  /// Xiaomi, Oppo, Vivo, Huawei and others kill background services
  /// regardless of what the AOSP rules say, and each hides the toggle in a
  /// different place. Resolves to a no-op on devices without one, so it is
  /// safe to offer unconditionally.
  static Future<void> openAutoStartSettings() =>
      _channel.invokeMethod('openAutoStartSettings');
}
