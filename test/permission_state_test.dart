import 'package:flutter_test/flutter_test.dart';
import 'package:reliable_background_location/reliable_background_location.dart';

void main() {
  group('PermissionState', () {
    PermissionState state({
      bool fine = true,
      bool background = true,
      bool notifications = true,
    }) => PermissionState(
      fineLocation: fine,
      coarseLocation: fine,
      backgroundLocation: background,
      notifications: notifications,
    );

    test('canStart needs fine location and notifications', () {
      expect(state().canStart, isTrue);
      expect(state(fine: false).canStart, isFalse);
      expect(state(notifications: false).canStart, isFalse);
    });

    test('background location alone does not allow a start', () {
      expect(state(fine: false, background: true).canStart, isFalse);
    });

    test('isComplete additionally needs background location', () {
      expect(state().isComplete, isTrue);
      expect(state(background: false).isComplete, isFalse);
      // The confusing middle state: starts, then pauses off-screen.
      expect(state(background: false).canStart, isTrue);
    });

    test('an empty map reads as nothing granted, not everything', () {
      final parsed = PermissionState.fromMap(const {});

      expect(parsed.fineLocation, isFalse);
      expect(parsed.notifications, isFalse);
      expect(parsed.canStart, isFalse);
    });

    test('parses the map the platform sends', () {
      final parsed = PermissionState.fromMap(const {
        'fineLocation': true,
        'coarseLocation': true,
        'backgroundLocation': false,
        'notifications': true,
      });

      expect(parsed.canStart, isTrue);
      expect(parsed.isComplete, isFalse);
    });
  });
}
