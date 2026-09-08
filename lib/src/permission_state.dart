/// What the tracking service is currently permitted to do.
///
/// Obtained from `ReliableBackgroundLocation.checkPermissions` and
/// `requestPermissions`.
class PermissionState {
  /// Creates a permission state.
  const PermissionState({
    required this.fineLocation,
    required this.coarseLocation,
    required this.backgroundLocation,
    required this.notifications,
  });

  /// `ACCESS_FINE_LOCATION` — required. Without it `startForeground` throws for
  /// a location-typed service, so tracking cannot begin at all.
  final bool fineLocation;

  /// `ACCESS_COARSE_LOCATION`. Informational: this package always asks for fine
  /// location, since a coarse track is not a track.
  final bool coarseLocation;

  /// `ACCESS_BACKGROUND_LOCATION`. Without it, fixes stop arriving once the app
  /// leaves the screen — tracking still starts, it just does not survive.
  ///
  /// Always true below Android 10, where no separate permission existed.
  final bool backgroundLocation;

  /// `POST_NOTIFICATIONS` — required on Android 13+, because a foreground
  /// service cannot exist without a visible notification.
  ///
  /// Always true below Android 13.
  final bool notifications;

  /// Whether tracking can start.
  bool get canStart => fineLocation && notifications;

  /// Whether tracking can start *and* keep recording in the background.
  ///
  /// This is the state to aim for. [canStart] without this produces the
  /// confusing bug report "it works while I watch it".
  bool get isComplete => canStart && backgroundLocation;

  /// Rebuilds the state from the map sent across the method channel.
  factory PermissionState.fromMap(Map<dynamic, dynamic> map) => PermissionState(
    fineLocation: map['fineLocation'] as bool? ?? false,
    coarseLocation: map['coarseLocation'] as bool? ?? false,
    backgroundLocation: map['backgroundLocation'] as bool? ?? false,
    notifications: map['notifications'] as bool? ?? false,
  );

  @override
  String toString() =>
      'PermissionState(fine: $fineLocation, '
      'background: $backgroundLocation, notifications: $notifications)';
}
