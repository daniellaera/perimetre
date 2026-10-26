// Base-map check: the tile layer and attribution of lib/map/map_tiles.dart at street level (zoom 17), light and
// dark, in Geneva and on the French side of the border. From app/, on a dev simulator (never the one holding a
// prod subscription), with the dev Mapbox token:
//   flutter drive --driver=test_driver/map_screenshots.dart --target=integration_test/map_tiles_test.dart \
//     --dart-define-from-file=config/dev.json -d <id>
// Screenshots land in build/map-screenshots/ (not committed). Fails when a tile does not load.
import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:latlong2/latlong.dart';
import 'package:perimetre/map/map_tiles.dart';

const places = {
  'geneve-centre': LatLng(46.2044, 6.1432),
  'annemasse': LatLng(46.1934, 6.2342),
  'ferney-voltaire': LatLng(46.2558, 6.1081),
};

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('base map at zoom 17, light and dark', (tester) async {
    await binding.convertFlutterSurfaceToImage();
    final errors = <String>[];
    for (final brightness in Brightness.values) {
      for (final MapEntry(key: name, value: center) in places.entries) {
        await tester.pumpWidget(
          MaterialApp(
            debugShowCheckedModeBanner: false,
            theme: ThemeData(colorSchemeSeed: const Color(0xFFC8102E), brightness: brightness),
            home: Builder(
              builder: (context) => Scaffold(
                body: FlutterMap(
                  key: ValueKey('$name-$brightness'),
                  options: MapOptions(
                    initialCenter: center,
                    initialZoom: 17,
                    backgroundColor: Theme.of(context).colorScheme.surfaceContainerHighest,
                  ),
                  children: [
                    baseTileLayer(brightness, onTileError: (error) => errors.add('$name ${brightness.name}: $error')),
                    CircleLayer(
                      circles: [
                        CircleMarker(
                          point: center,
                          radius: 100,
                          useRadiusInMeter: true,
                          color: const Color(0x1FC8102E),
                          borderColor: const Color(0xB3C8102E),
                          borderStrokeWidth: 2,
                        ),
                      ],
                    ),
                    const MapAttribution(),
                  ],
                ),
              ),
            ),
          ),
        );
        // Tiles come from the network: give them time, then let the fade-in finish.
        for (var i = 0; i < 60; i++) {
          await tester.pump(const Duration(milliseconds: 100));
        }
        await binding.takeScreenshot('$name-${brightness.name}${useMapbox ? '' : '-osm-dev'}');
      }
    }
    expect(errors, isEmpty);
  });
}
