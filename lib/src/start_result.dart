/// Why a call to start tracking did not result in a running service.
///
/// Android refuses to start a foreground service for several unrelated
/// reasons, and it does so by throwing rather than by returning a code.
/// A refused `startForeground()` does not merely hide the notification —
/// the service is killed, so a plugin that ignores the exception leaves the
/// caller believing tracking is live when nothing is recording.
enum StartFailure {
  /// `ACCESS_FINE_LOCATION` (or coarse) has not been granted, or was
  /// revoked while the app sat in the background.
  locationPermissionMissing,

  /// Foreground permission is granted but `ACCESS_BACKGROUND_LOCATION` is
  /// not, so fixes stop the moment the app leaves the screen.
  ///
  /// On Android 11+ this cannot be requested in the same prompt as
  /// foreground location; the user has to be sent to system settings.
  backgroundLocationPermissionMissing,

  /// Android 13+ requires `POST_NOTIFICATIONS` before a foreground service
  /// notification can be shown, and without the notification the service
  /// cannot exist.
  notificationPermissionMissing,

  /// Android 12+ forbids starting a foreground service while the app is in
  /// the background, unless an exemption applies.
  ///
  /// This is the failure that catches teams out after an SDK bump: the same
  /// code that worked for years starts throwing
  /// `ForegroundServiceStartNotAllowedException`. Start tracking from a
  /// foreground interaction, or hold an exemption such as an active
  /// notification action.
  notAllowedFromBackground,

  /// Location is switched off device-wide, so no provider can produce a fix.
  locationServicesDisabled,

  /// Something else went wrong. Read [StartResult.message] before filing a
  /// bug — it carries the platform exception verbatim.
  unknown,
}

/// The outcome of a request to begin tracking.
///
/// Check [started] before assuming anything is being recorded.
class StartResult {
  /// Creates a successful result, optionally carrying recovered state.
  const StartResult.success({this.recoveredSessionState})
    : started = true,
      failure = null,
      message = null;

  /// Creates a failed result.
  const StartResult.failure(this.failure, this.message)
    : started = false,
      recoveredSessionState = null;

  /// Whether the foreground service is now running and recording.
  final bool started;

  /// Why the start failed, or null when [started] is true.
  final StartFailure? failure;

  /// The underlying platform message, when there was one.
  final String? message;

  /// State handed back from a session the OS interrupted.
  ///
  /// When Android kills the process mid-session it may later restart the
  /// service on its own, with no Dart isolate alive to notice. Whatever you
  /// passed as `sessionState` is persisted natively and returned here on the
  /// next successful start, so you can resume rather than begin again.
  ///
  /// Null when the previous session ended cleanly.
  final Map<String, String>? recoveredSessionState;

  /// Rebuilds a result from the map sent across the method channel.
  factory StartResult.fromMap(Map<dynamic, dynamic> map) {
    if (map['started'] == true) {
      final state = map['recoveredSessionState'] as Map<dynamic, dynamic>?;
      return StartResult.success(
        recoveredSessionState: state?.map(
          (k, v) => MapEntry(k.toString(), v.toString()),
        ),
      );
    }
    return StartResult.failure(
      StartFailure.values.firstWhere(
        (f) => f.name == map['failure'],
        orElse: () => StartFailure.unknown,
      ),
      map['message'] as String?,
    );
  }

  @override
  String toString() => started
      ? 'StartResult.success(recovered: ${recoveredSessionState != null})'
      : 'StartResult.failure(${failure?.name}: $message)';
}
