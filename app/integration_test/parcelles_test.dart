// The parcels block of the details sheet with REAL backend data (local DB imported, `./gradlew bootRun`
// running with parcels imported): one dossier with 1 parcel, one with 2, one with none, each opened in the real
// details sheet, light and dark. From app/, on a dev simulator (never the one holding a prod subscription):
//   flutter drive --driver=test_driver/parcelles_screenshots.dart --target=integration_test/parcelles_test.dart -d <id>
// Screenshots land in build/parcel-screenshots/ (not committed).
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:perimetre/api/models.dart';
import 'package:perimetre/features/explore/widgets.dart';

const base = String.fromEnvironment('API_BASE_URL', defaultValue: 'http://localhost:8090');

Future<List<Dossier>> around(double lat, double lng, int radius) async {
  final res = await Dio().get<List<dynamic>>(
    '$base/dossiers',
    queryParameters: {'lat': lat, 'lng': lng, 'radius': radius},
  );
  return [for (final j in res.data!) Dossier.fromJson(j as Map<String, dynamic>)];
}

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('details sheet with 1, 2 and 0 parcels from the real API', (tester) async {
    await binding.convertFlutterSurfaceToImage();
    final geneva = await around(46.2044, 6.1432, 2000);
    final one = geneva.firstWhere((d) => d.parcelles.length == 1);
    final two = geneva.firstWhere((d) => d.parcelles.length == 2);
    // The one local dossier outside every parcel (a point in the public domain).
    final none = (await around(46.184026, 6.194948, 5)).firstWhere((d) => d.idDossier == 'DD 348411/1');
    expect(none.parcelles, isEmpty);
    // A real payload carries the portal fallback on every dossier.
    expect(one.lienRegistreFoncier, registreFoncierUrl);

    for (final brightness in Brightness.values) {
      for (final (name, d) in [('1-parcelle', one), ('2-parcelles', two), ('0-parcelle', none)]) {
        await tester.pumpWidget(
          MaterialApp(
            debugShowCheckedModeBanner: false,
            theme: ThemeData(colorSchemeSeed: const Color(0xFFC8102E), brightness: brightness),
            home: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: TextButton(onPressed: () => showDossierDetails(context, d), child: const Text('open')),
                ),
              ),
            ),
          ),
        );
        await tester.tap(find.text('open'));
        await tester.pumpAndSettle();
        if (d.parcelles.isEmpty) {
          expect(find.textContaining('Parcelle'), findsNothing);
        } else {
          expect(find.text('Voir au registre foncier'), findsNWidgets(d.parcelles.length));
          expect(find.textContaining('EGRID'), findsNWidgets(d.parcelles.length));
        }
        // Scroll the sheet to its end so the parcels block is on screen.
        await tester.drag(find.byType(SingleChildScrollView).last, const Offset(0, -2000));
        await tester.pumpAndSettle();
        await binding.takeScreenshot('${brightness.name}-$name');
        Navigator.of(tester.element(find.byType(BottomSheet))).pop();
        await tester.pumpAndSettle();
      }
    }
  });
}
