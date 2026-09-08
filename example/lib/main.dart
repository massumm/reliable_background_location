import 'dart:async';

import 'package:flutter/material.dart';
import 'package:reliable_background_location/reliable_background_location.dart';

void main() => runApp(const DemoApp());

class DemoApp extends StatelessWidget {
  const DemoApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Background location demo',
      theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
      home: const TrackingPage(),
    );
  }
}

class TrackingPage extends StatefulWidget {
  const TrackingPage({super.key});

  @override
  State<TrackingPage> createState() => _TrackingPageState();
}

class _TrackingPageState extends State<TrackingPage> {
  final _track = <LocationSample>[];
  StreamSubscription<LocationSample>? _sub;

  bool _running = false;
  bool _batterySaver = false;
  String? _error;
  int _recoveredCount = 0;

  @override
  void initState() {
    super.initState();
    _restoreAfterProcessDeath();
  }

  /// Drain anything the service recorded while this isolate was dead, then
  /// re-attach to the live stream. Doing it in this order is what makes a
  /// track continuous across a process kill.
  Future<void> _restoreAfterProcessDeath() async {
    final buffered = await ReliableBackgroundLocation.drainBuffered();
    final running = await ReliableBackgroundLocation.isRunning();
    final saver = await ReliableBackgroundLocation.isBatterySaverOn();

    if (!mounted) return;
    setState(() {
      _track.addAll(buffered);
      _recoveredCount = buffered.length;
      _running = running;
      _batterySaver = saver;
    });

    if (running) _listen();
  }

  void _listen() {
    _sub ??= ReliableBackgroundLocation.locations.listen((sample) {
      if (!mounted) return;
      setState(() => _track.add(sample));
      // Android rate-limits notification updates, so only refresh the text
      // every tenth fix rather than on all of them.
      if (_track.length % 10 == 0) {
        ReliableBackgroundLocation.updateNotification(
          text: '${_track.length} fixes recorded',
        );
      }
    });
  }

  /// Asks for what the service needs and reports honestly what came back.
  ///
  /// The plugin handles the ordering — foreground location and notifications
  /// first, background location as its own prompt afterwards — so this only
  /// has to decide what to do with the answer.
  Future<bool> _ensurePermissions() async {
    final state = await ReliableBackgroundLocation.requestPermissions();

    if (!state.canStart) {
      setState(
        () => _error = state.fineLocation
            ? 'Notification permission denied. A foreground service cannot run '
                  'without a notification, so tracking cannot start.'
            : 'Location permission denied. Tracking cannot start without it.',
      );
      return false;
    }

    if (!state.isComplete) {
      // Not fatal — tracking runs, it just will not survive the app leaving
      // the screen. Saying so beats a later "it stopped by itself" report.
      setState(
        () => _error =
            'Background location not granted — tracking will '
            'pause when the app leaves the screen. Choose "Allow all the time" '
            'in system settings to fix it.',
      );
    }

    return true;
  }

  Future<void> _start() async {
    setState(() => _error = null);

    if (!await _ensurePermissions()) return;
    if (!mounted) return;

    final result = await ReliableBackgroundLocation.start(
      notification: const NotificationConfig(
        title: 'Recording your route',
        text: 'Tap to return to the app',
      ),
      location: const LocationConfig(interval: Duration(seconds: 3)),
      sessionState: {'startedAt': DateTime.now().toIso8601String()},
    );

    if (!mounted) return;

    // The whole point: a refused start is reported, not swallowed. Showing
    // "recording" here when the service never came up is the bug this
    // package exists to prevent.
    if (!result.started) {
      setState(() => _error = '${result.failure?.name}: ${result.message}');
      return;
    }

    if (result.recoveredSessionState != null) {
      debugPrint('resumed session: ${result.recoveredSessionState}');
    }

    setState(() => _running = true);
    _listen();
  }

  Future<void> _stop() async {
    await ReliableBackgroundLocation.stop();
    await _sub?.cancel();
    _sub = null;
    if (mounted) setState(() => _running = false);
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Background location')),
      body: Column(
        children: [
          if (_batterySaver)
            _Banner(
              colour: Colors.orange.shade100,
              text: 'Battery saver is on. Fixes will arrive less often.',
              actionLabel: 'Settings',
              onAction:
                  ReliableBackgroundLocation.openBatteryOptimisationSettings,
            ),
          if (_error != null)
            _Banner(colour: Colors.red.shade100, text: _error!),
          if (_recoveredCount > 0)
            _Banner(
              colour: Colors.green.shade100,
              text: 'Recovered $_recoveredCount fix(es) from a previous run.',
            ),
          Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                Expanded(
                  child: FilledButton.icon(
                    onPressed: _running ? null : _start,
                    icon: const Icon(Icons.play_arrow),
                    label: const Text('Start'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: _running ? _stop : null,
                    icon: const Icon(Icons.stop),
                    label: const Text('Stop'),
                  ),
                ),
              ],
            ),
          ),
          Text('${_track.length} fixes', style: const TextStyle(fontSize: 18)),
          const Divider(),
          Expanded(
            child: _track.isEmpty
                ? const Center(child: Text('No fixes yet'))
                : ListView.builder(
                    reverse: true,
                    itemCount: _track.length,
                    itemBuilder: (context, i) {
                      final s = _track[_track.length - 1 - i];
                      return ListTile(
                        dense: true,
                        title: Text(
                          '${s.latitude.toStringAsFixed(5)}, '
                          '${s.longitude.toStringAsFixed(5)}',
                        ),
                        subtitle: Text(
                          '±${s.accuracy?.toStringAsFixed(0) ?? '?'} m  ·  '
                          '${s.timestamp.toLocal().toIso8601String().substring(11, 19)}'
                          '${s.isMocked ? '  ·  MOCKED' : ''}',
                        ),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}

class _Banner extends StatelessWidget {
  const _Banner({
    required this.colour,
    required this.text,
    this.actionLabel,
    this.onAction,
  });

  final Color colour;
  final String text;
  final String? actionLabel;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      color: colour,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      child: Row(
        children: [
          Expanded(child: Text(text)),
          if (actionLabel != null)
            TextButton(onPressed: onAction, child: Text(actionLabel!)),
        ],
      ),
    );
  }
}
