import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:reliable_background_location/reliable_background_location.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('reliable_background_location/service');
  final calls = <MethodCall>[];
  Object? reply;

  setUp(() {
    calls.clear();
    reply = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          calls.add(call);
          return reply;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  group('start', () {
    test('forwards notification, location config and session state', () async {
      reply = {'started': true};

      await ReliableBackgroundLocation.start(
        notification: const NotificationConfig(
          title: 'Recording',
          text: 'Tap to return',
          smallIconResource: 'ic_track',
        ),
        location: const LocationConfig(
          accuracy: LocationAccuracy.balanced,
          interval: Duration(seconds: 10),
          minDistance: 5,
        ),
        sessionState: const {'sessionId': 'x1'},
      );

      expect(calls.single.method, 'start');
      final args = calls.single.arguments as Map;
      expect(args['notification']['title'], 'Recording');
      expect(args['notification']['smallIconResource'], 'ic_track');
      expect(args['location']['accuracy'], 'balanced');
      expect(args['location']['intervalMs'], 10000);
      expect(args['location']['minDistance'], 5);
      expect(args['sessionState'], {'sessionId': 'x1'});
    });

    test('reports failure when the platform refuses to start', () async {
      reply = {
        'started': false,
        'failure': 'notificationPermissionMissing',
        'message': 'POST_NOTIFICATIONS denied',
      };

      final result = await ReliableBackgroundLocation.start(
        notification: const NotificationConfig(title: 't', text: 'x'),
      );

      expect(result.started, isFalse);
      expect(result.failure, StartFailure.notificationPermissionMissing);
    });

    test('reports failure when the platform returns nothing at all', () async {
      reply = null;

      final result = await ReliableBackgroundLocation.start(
        notification: const NotificationConfig(title: 't', text: 'x'),
      );

      expect(result.started, isFalse);
    });
  });

  group('drainBuffered', () {
    test('parses buffered samples', () async {
      reply = [
        {'latitude': 1.0, 'longitude': 2.0, 'timestamp': 1757300000000},
        {'latitude': 3.0, 'longitude': 4.0, 'timestamp': 1757300005000},
      ];

      final samples = await ReliableBackgroundLocation.drainBuffered();

      expect(samples, hasLength(2));
      expect(samples.first.latitude, 1.0);
      expect(samples.last.longitude, 4.0);
    });

    test('returns an empty list when the buffer is empty', () async {
      reply = null;

      expect(await ReliableBackgroundLocation.drainBuffered(), isEmpty);
    });
  });

  group('permissions', () {
    test('checkPermissions parses the platform map', () async {
      reply = {
        'fineLocation': true,
        'coarseLocation': true,
        'backgroundLocation': false,
        'notifications': true,
      };

      final state = await ReliableBackgroundLocation.checkPermissions();

      expect(calls.single.method, 'checkPermissions');
      expect(state.canStart, isTrue);
      expect(state.isComplete, isFalse);
    });

    test('requestPermissions treats no reply as nothing granted', () async {
      reply = null;

      final state = await ReliableBackgroundLocation.requestPermissions();

      expect(state.canStart, isFalse);
    });
  });

  group('state queries', () {
    test('isRunning defaults to false rather than throwing', () async {
      reply = null;
      expect(await ReliableBackgroundLocation.isRunning(), isFalse);
    });

    test('isBatterySaverOn defaults to false rather than throwing', () async {
      reply = null;
      expect(await ReliableBackgroundLocation.isBatterySaverOn(), isFalse);
    });

    test('updateNotification forwards only what was supplied', () async {
      await ReliableBackgroundLocation.updateNotification(text: '5.2 km');

      final args = calls.single.arguments as Map;
      expect(args['title'], isNull);
      expect(args['text'], '5.2 km');
    });
  });
}
