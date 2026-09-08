/// A single position fix delivered by the platform location provider.
///
/// This is deliberately a plain value type rather than a re-export from
/// another location package. Depending on `geolocator` (or any other
/// position type) would force every app using this plugin onto the same
/// major version, which is the most common reason a location plugin becomes
/// unusable inside a real dependency tree.
class LocationSample {
  /// Creates a sample. Only [latitude], [longitude] and [timestamp] are
  /// guaranteed to be present on every Android version and provider.
  const LocationSample({
    required this.latitude,
    required this.longitude,
    required this.timestamp,
    this.accuracy,
    this.altitude,
    this.speed,
    this.heading,
    this.isMocked = false,
  });

  /// Degrees north of the equator.
  final double latitude;

  /// Degrees east of the prime meridian.
  final double longitude;

  /// When the fix was produced by the provider, not when Dart received it.
  ///
  /// Buffered samples can arrive minutes after the fact, so always prefer
  /// this over `DateTime.now()` when reconstructing a track.
  final DateTime timestamp;

  /// Horizontal accuracy in metres, if the provider reported one.
  ///
  /// Treat a large value as "the user could be anywhere in this circle".
  /// Filtering on this is usually necessary: the first fixes after a cold
  /// start are routinely accurate to 50 m or worse.
  final double? accuracy;

  /// Metres above the WGS 84 reference ellipsoid, if reported.
  final double? altitude;

  /// Ground speed in metres per second, if reported.
  final double? speed;

  /// Direction of travel in degrees clockwise from true north, if reported.
  final double? heading;

  /// Whether Android flagged this fix as coming from a mock provider.
  ///
  /// Worth checking for anything competitive — a running or delivery app,
  /// for instance — since enabling a mock provider needs no root.
  final bool isMocked;

  /// Rebuilds a sample from the map sent across the method channel.
  factory LocationSample.fromMap(Map<dynamic, dynamic> map) {
    return LocationSample(
      latitude: (map['latitude'] as num).toDouble(),
      longitude: (map['longitude'] as num).toDouble(),
      timestamp: DateTime.fromMillisecondsSinceEpoch(
        (map['timestamp'] as num).toInt(),
        isUtc: true,
      ),
      accuracy: (map['accuracy'] as num?)?.toDouble(),
      altitude: (map['altitude'] as num?)?.toDouble(),
      speed: (map['speed'] as num?)?.toDouble(),
      heading: (map['heading'] as num?)?.toDouble(),
      isMocked: map['isMocked'] as bool? ?? false,
    );
  }

  /// Serialises the sample, mirroring [LocationSample.fromMap].
  Map<String, Object?> toMap() => {
    'latitude': latitude,
    'longitude': longitude,
    'timestamp': timestamp.toUtc().millisecondsSinceEpoch,
    'accuracy': accuracy,
    'altitude': altitude,
    'speed': speed,
    'heading': heading,
    'isMocked': isMocked,
  };

  @override
  String toString() =>
      'LocationSample($latitude, $longitude, ${accuracy?.toStringAsFixed(1)}m, '
      '${timestamp.toIso8601String()})';
}
