import 'package:flutter_test/flutter_test.dart';
import 'package:reliable_background_location/reliable_background_location.dart';

void main() {
  group('LocationSample', () {
    test('round-trips through toMap/fromMap', () {
      final original = LocationSample(
        latitude: 23.7806,
        longitude: 90.4074,
        timestamp: DateTime.utc(2026, 9, 8, 4, 30),
        accuracy: 4.5,
        altitude: 8.2,
        speed: 3.1,
        heading: 271.0,
        isMocked: true,
      );

      final restored = LocationSample.fromMap(original.toMap());

      expect(restored.latitude, original.latitude);
      expect(restored.longitude, original.longitude);
      expect(restored.timestamp, original.timestamp);
      expect(restored.accuracy, original.accuracy);
      expect(restored.altitude, original.altitude);
      expect(restored.speed, original.speed);
      expect(restored.heading, original.heading);
      expect(restored.isMocked, isTrue);
    });

    test('accepts a fix carrying only the guaranteed fields', () {
      final sample = LocationSample.fromMap({
        'latitude': 1.5,
        'longitude': 2.5,
        'timestamp': 1757300000000,
      });

      expect(sample.latitude, 1.5);
      expect(sample.accuracy, isNull);
      expect(sample.isMocked, isFalse);
    });

    test('reads integer coordinates sent as num', () {
      final sample = LocationSample.fromMap({
        'latitude': 24,
        'longitude': 90,
        'timestamp': 1757300000000,
      });

      expect(sample.latitude, 24.0);
      expect(sample.longitude, 90.0);
    });

    test('keeps the provider timestamp in UTC', () {
      final sample = LocationSample.fromMap({
        'latitude': 0,
        'longitude': 0,
        'timestamp': 1757300000000,
      });

      expect(sample.timestamp.isUtc, isTrue);
      expect(sample.timestamp.millisecondsSinceEpoch, 1757300000000);
    });
  });
}
