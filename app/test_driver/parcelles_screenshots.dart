// Driver for integration_test/parcelles_test.dart: writes each screenshot to build/parcel-screenshots/<name>.png.
import 'dart:io';

import 'package:integration_test/integration_test_driver_extended.dart';

Future<void> main() => integrationDriver(
  onScreenshot: (name, bytes, [args]) async {
    final file = File('build/parcel-screenshots/$name.png');
    await file.create(recursive: true);
    await file.writeAsBytes(bytes);
    return true;
  },
);
