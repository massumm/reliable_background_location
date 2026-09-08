/// How hard the platform should work to produce a fix.
///
/// Maps onto Play Services' `Priority` constants.
enum LocationAccuracy {
  /// GPS plus every other sensor available. Highest battery cost, and the
  /// only setting that produces a usable track for walking or running.
  high,

  /// Roughly city-block accuracy from Wi-Fi and cell towers.
  balanced,

  /// Cell towers only. Cheap, accurate to a kilometre or so.
  low,

  /// No active fixes; only positions other apps have already requested.
  passive,
}

/// The notification Android shows for as long as tracking runs.
///
/// A foreground service without a notification is not a thing the platform
/// allows, so this is required rather than optional. Treat the text as part
/// of your UI: it is the only thing standing between your service and a user
/// who thinks the app is spying on them.
class NotificationConfig {
  /// Creates a notification configuration.
  const NotificationConfig({
    required this.title,
    required this.text,
    this.channelId = 'reliable_background_location',
    this.channelName = 'Location tracking',
    this.channelDescription =
        'Shown while your location is being recorded in the background.',
    this.smallIconResource,
  });

  /// Bold first line, e.g. `Recording your run`.
  final String title;

  /// Second line. Live stats belong here — see
  /// `ReliableBackgroundLocation.updateNotification`.
  final String text;

  /// Notification channel id. Changing this after release strands the
  /// old channel in the user's settings, so pick one and keep it.
  final String channelId;

  /// Channel name as it appears in Android's notification settings.
  final String channelName;

  /// Channel description shown under [channelName] in system settings.
  final String channelDescription;

  /// Drawable name for the status-bar icon, without extension or folder,
  /// e.g. `ic_tracking`. Falls back to the app icon when null.
  ///
  /// Android renders this as a silhouette: supply a single-colour, fully
  /// opaque shape or it will look like a grey blob.
  final String? smallIconResource;

  /// Serialises the config for the method channel.
  Map<String, Object?> toMap() => {
    'title': title,
    'text': text,
    'channelId': channelId,
    'channelName': channelName,
    'channelDescription': channelDescription,
    'smallIconResource': smallIconResource,
  };
}

/// How often and how accurately to sample position.
class LocationConfig {
  /// Creates a location configuration.
  const LocationConfig({
    this.accuracy = LocationAccuracy.high,
    this.interval = const Duration(seconds: 5),
    this.minInterval = const Duration(seconds: 2),
    this.minDistance = 0,
    this.bufferWhileDetached = true,
    this.maxBufferedSamples = 5000,
  });

  /// Requested accuracy. Defaults to [LocationAccuracy.high].
  final LocationAccuracy accuracy;

  /// How often you would like a fix.
  ///
  /// This is a request, not a promise. Android batches aggressively in Doze
  /// and will happily give you one fix a minute regardless of what you ask.
  final Duration interval;

  /// The fastest rate you are willing to accept fixes.
  ///
  /// Set this when another app's higher-frequency request could otherwise
  /// flood your callback.
  final Duration minInterval;

  /// Minimum movement in metres before a new fix is delivered.
  ///
  /// Leave at 0 for continuous tracks. Raising it saves battery but produces
  /// corners rather than curves.
  final double minDistance;

  /// Whether the native side keeps samples while no Dart isolate is alive.
  ///
  /// This is the whole point of the plugin. With this on, fixes collected
  /// while your process was dead are retrievable via
  /// `ReliableBackgroundLocation.drainBuffered`.
  final bool bufferWhileDetached;

  /// Hard cap on buffered samples, oldest dropped first.
  ///
  /// At the default 5-second interval, 5000 samples is roughly seven hours.
  final int maxBufferedSamples;

  /// Serialises the config for the method channel.
  Map<String, Object?> toMap() => {
    'accuracy': accuracy.name,
    'intervalMs': interval.inMilliseconds,
    'minIntervalMs': minInterval.inMilliseconds,
    'minDistance': minDistance,
    'bufferWhileDetached': bufferWhileDetached,
    'maxBufferedSamples': maxBufferedSamples,
  };
}
