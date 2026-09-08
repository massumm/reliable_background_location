import 'package:flutter_test/flutter_test.dart';
import 'package:reliable_background_location/reliable_background_location.dart';

void main() {
  group('StartResult', () {
    test('parses a plain success', () {
      final result = StartResult.fromMap({'started': true});

      expect(result.started, isTrue);
      expect(result.failure, isNull);
      expect(result.recoveredSessionState, isNull);
    });

    test('parses recovered session state, stringifying values', () {
      final result = StartResult.fromMap({
        'started': true,
        'recoveredSessionState': {'sessionId': 'abc', 'metres': 1420},
      });

      expect(result.recoveredSessionState, {
        'sessionId': 'abc',
        'metres': '1420',
      });
    });

    test('maps a known failure name onto the enum', () {
      final result = StartResult.fromMap({
        'started': false,
        'failure': 'notAllowedFromBackground',
        'message': 'ForegroundServiceStartNotAllowedException',
      });

      expect(result.started, isFalse);
      expect(result.failure, StartFailure.notAllowedFromBackground);
      expect(result.message, contains('ForegroundService'));
    });

    test('falls back to unknown for a failure it does not recognise', () {
      final result = StartResult.fromMap({
        'started': false,
        'failure': 'somethingNewInAndroid17',
        'message': 'nope',
      });

      expect(result.failure, StartFailure.unknown);
    });

    test('treats a missing started flag as failure, not success', () {
      final result = StartResult.fromMap(const {});

      expect(result.started, isFalse);
      expect(result.failure, StartFailure.unknown);
    });
  });
}
