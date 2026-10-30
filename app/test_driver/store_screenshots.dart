// Driver for integration_test/store_screenshots_test.dart. Serves http://localhost:8765/shot?name=<name> to the
// app, which the simulator reaches on the Mac's loopback: each request screenshots SIMULATOR_ID with
// `xcrun simctl io` (full screen, status bar included) into ../docs/app-store/<name>.png, without alpha channel.
// Before the test it switches the simulator to light mode and fixes the status bar (9:41, full battery and signal).
import 'dart:io';

import 'package:integration_test/integration_test_driver.dart';

Future<void> run(String executable, List<String> args) async {
  final result = await Process.run(executable, args);
  if (result.exitCode != 0) throw Exception('$executable ${args.join(' ')}: ${result.stderr}');
}

Future<void> simctl(List<String> args) => run('xcrun', ['simctl', ...args]);

/// App Store Connect refuses screenshots with an alpha channel: a round trip through JPEG (quality 100) drops it.
Future<void> dropAlpha(File png) async {
  final jpeg = '${png.path}.jpg';
  await run('sips', ['-s', 'format', 'jpeg', '-s', 'formatOptions', '100', png.path, '--out', jpeg]);
  await run('sips', ['-s', 'format', 'png', jpeg, '--out', png.path]);
  await File(jpeg).delete();
}

Future<void> main() async {
  final id = Platform.environment['SIMULATOR_ID'];
  if (id == null || id.isEmpty) throw Exception('Set SIMULATOR_ID to the simulator passed to -d');
  await simctl(['ui', id, 'appearance', 'light']);
  await simctl([
    'status_bar', id, 'override', '--time', '9:41', '--dataNetwork', 'wifi', '--wifiMode', 'active', //
    '--wifiBars', '3', '--cellularMode', 'active', '--cellularBars', '4', '--operatorName', '', //
    '--batteryState', 'charged', '--batteryLevel', '100',
  ]);

  final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 8765);
  server.listen((request) async {
    final name = request.uri.queryParameters['name'] ?? '';
    if (request.uri.path != '/shot' || !RegExp(r'^[\w-]+$').hasMatch(name)) {
      request.response.statusCode = HttpStatus.badRequest;
    } else {
      final file = File('../docs/app-store/$name.png');
      await file.parent.create(recursive: true);
      await simctl(['io', id, 'screenshot', '--type=png', file.path]);
      await dropAlpha(file);
      stdout.writeln('screenshot ${file.path}');
    }
    await request.response.close();
  });

  await integrationDriver();
}
