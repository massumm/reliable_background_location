import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:reliable_background_location_example/main.dart';

void main() {
  testWidgets('shows the empty state before any fix arrives', (tester) async {
    await tester.pumpWidget(const DemoApp());
    await tester.pump();

    expect(find.text('No fixes yet'), findsOneWidget);
    expect(find.widgetWithText(FilledButton, 'Start'), findsOneWidget);
  });

  testWidgets('Stop is disabled until tracking starts', (tester) async {
    await tester.pumpWidget(const DemoApp());
    await tester.pump();

    final stop = tester.widget<OutlinedButton>(
      find.widgetWithText(OutlinedButton, 'Stop'),
    );
    expect(stop.onPressed, isNull);
  });
}
