import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:url_launcher/url_launcher.dart';

/// Mapbox public token (`pk.…`), from `--dart-define-from-file=config/dev.json` (debug, simulator) or
/// `config/prod.json` (release). Both files are git-ignored; see `config/example.json`. A Release build of the
/// iOS app fails without it (Runner build phase "Check Mapbox token").
const mapboxAccessToken = String.fromEnvironment('MAPBOX_ACCESS_TOKEN');

/// Without a token, debug and profile builds fall back to OpenStreetMap's public tiles, which their usage
/// policy only tolerates for development.
bool get useMapbox => mapboxAccessToken.isNotEmpty || kReleaseMode;

const _userAgentPackageName = 'ch.perimetre.app';

/// The base map: Mapbox Light or Dark (Static Tiles API, 512 px tiles at @2x) following the theme.
/// [onTileError] is for integration_test/map_tiles_test.dart.
TileLayer baseTileLayer(Brightness brightness, {void Function(Object error)? onTileError}) {
  final errorTileCallback = onTileError == null
      ? null
      : (TileImage _, Object error, StackTrace? _) => onTileError(error);
  if (!useMapbox) {
    return TileLayer(
      urlTemplate: 'https://tile.openstreetmap.org/{z}/{x}/{y}.png',
      userAgentPackageName: _userAgentPackageName,
      errorTileCallback: errorTileCallback,
    );
  }
  final style = brightness == Brightness.dark ? 'dark-v11' : 'light-v11';
  return TileLayer(
    // The key makes flutter_map drop the other style's tiles when the theme changes.
    key: ValueKey(style),
    urlTemplate: 'https://api.mapbox.com/styles/v1/mapbox/$style/tiles/512/{z}/{x}/{y}@2x?access_token={token}',
    additionalOptions: const {'token': mapboxAccessToken},
    // 512 px tiles cover what four 256 px tiles do (a quarter of the billed requests), one zoom level up.
    tileDimension: 512,
    zoomOffset: -1,
    maxNativeZoom: 22,
    userAgentPackageName: _userAgentPackageName,
    errorTileCallback: errorTileCallback,
  );
}

/// The attribution Mapbox requires on its maps (docs.mapbox.com/help/dive-deeper/attribution): the Mapbox logo,
/// and "© Mapbox", "© OpenStreetMap" and "Improve this map" as links, always visible. At the top of the map,
/// because the dossier sheet covers its bottom. A child of [FlutterMap] (it reads the camera).
class MapAttribution extends StatelessWidget {
  const MapAttribution({super.key});

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final style = TextStyle(fontSize: 11, color: scheme.onSurface);
    final links = useMapbox
        ? [
            _Link('© Mapbox', 'https://www.mapbox.com/', style),
            _Link('© OpenStreetMap', 'https://www.openstreetmap.org/copyright', style),
            _Link('Improve this map', _feedbackUrl(MapCamera.of(context)), style, bold: true),
          ]
        : [_Link('© OpenStreetMap contributors', 'https://www.openstreetmap.org/copyright', style)];
    return SafeArea(
      bottom: false,
      child: Padding(
        padding: const EdgeInsets.all(4),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (useMapbox)
              GestureDetector(
                onTap: () => launchUrl(Uri.parse('https://www.mapbox.com/')),
                child: Image.asset('assets/mapbox-logo.png', width: 88, height: 23, semanticLabel: 'Mapbox'),
              ),
            const SizedBox(width: 8),
            Expanded(
              child: Align(
                alignment: Alignment.topRight,
                child: DecoratedBox(
                  decoration: BoxDecoration(
                    color: scheme.surface.withValues(alpha: 0.75),
                    borderRadius: BorderRadius.circular(4),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
                    child: Wrap(alignment: WrapAlignment.end, spacing: 6, children: links),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  static String _feedbackUrl(MapCamera camera) {
    final c = camera.center;
    return 'https://apps.mapbox.com/feedback/#/${c.longitude.toStringAsFixed(5)}/'
        '${c.latitude.toStringAsFixed(5)}/${camera.zoom.round()}';
  }
}

class _Link extends StatelessWidget {
  const _Link(this.text, this.url, this.style, {this.bold = false});

  final String text;
  final String url;
  final TextStyle style;
  final bool bold;

  @override
  Widget build(BuildContext context) => GestureDetector(
    onTap: () => launchUrl(Uri.parse(url)),
    child: Text(text, style: bold ? style.copyWith(fontWeight: FontWeight.w600) : style),
  );
}
