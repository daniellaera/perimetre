// Driver for integration_test/parcelles_test.dart. Serves http://localhost:8766/shot?name=<name>, which the app
// reaches on the Mac's loopback: each request screenshots the simulator named by SIMULATOR_ID with
// `xcrun simctl io` (the whole screen, so the native in-app Safari sheet is captured too) into
// build/parcel-screenshots/<name>.png. Pass the DEV simulator's id, never the one holding a prod subscription.
import 'dart:io';

import 'package:integration_test/integration_test_driver.dart';

Future<void> main() async {
  final id = Platform.environment['SIMULATOR_ID'];
  if (id == null || id.isEmpty) throw Exception('Set SIMULATOR_ID to the simulator passed to -d');
  final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 8766);
  server.listen((request) async {
    final name = request.uri.queryParameters['name'] ?? '';
    if (request.uri.path != '/shot' || !RegExp(r'^[\w-]+$').hasMatch(name)) {
      request.response.statusCode = HttpStatus.badRequest;
    } else {
      final file = File('build/parcel-screenshots/$name.png');
      await file.parent.create(recursive: true);
      final result = await Process.run('xcrun', ['simctl', 'io', id, 'screenshot', '--type=png', file.path]);
      if (result.exitCode != 0) request.response.statusCode = HttpStatus.internalServerError;
      stdout.writeln('screenshot ${file.path}');
    }
    await request.response.close();
  });
  await integrationDriver();
}
