// The parcels block of the details sheet with REAL backend data (local DB with parcels, buildings, addresses and
// zones imported, `./gradlew bootRun` running): a dossier with 1 parcel, one with 2, one with none, each opened in the
// real details sheet, light and dark, collapsed and with every section expanded. Then the "Voir au registre foncier"
// button is tapped: the in-app Safari sheet must open over the app and close back to it. From app/, on a DEV
// simulator (never the one holding a prod subscription):
//   SIMULATOR_ID=<id> flutter drive --driver=test_driver/parcelles_screenshots.dart \
//     --target=integration_test/parcelles_test.dart -d <id>
// Screenshots land in build/parcel-screenshots/ (not committed).
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:perimetre/api/models.dart';
import 'package:perimetre/features/explore/widgets.dart';
import 'package:url_launcher/url_launcher.dart';

const base = String.fromEnvironment('API_BASE_URL', defaultValue: 'http://localhost:8090');

Future<List<Dossier>> around(double lat, double lng, int radius) async {
  final res = await Dio().get<List<dynamic>>(
    '$base/dossiers',
    queryParameters: {'lat': lat, 'lng': lng, 'radius': radius},
  );
  return [for (final j in res.data!) Dossier.fromJson(j as Map<String, dynamic>)];
}

/// Asks the driver's server on the Mac to screenshot the simulator (the frame stays as pumped meanwhile).
Future<void> shot(String name) async {
  final host = Dio(BaseOptions(baseUrl: 'http://localhost:8766', receiveTimeout: const Duration(seconds: 30)));
  await host.get<void>('/shot', queryParameters: {'name': name});
}

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('details sheet with 1, 2 and 0 parcels from the real API, then the in-app browser', (tester) async {
    final geneva = await around(46.2044, 6.1432, 2000);
    bool rich(Parcelle p) => p.batiments.isNotEmpty && p.restrictions.isNotEmpty && p.lieuDit != null;
    final one = geneva.firstWhere((d) => d.parcelles.length == 1 && rich(d.parcelles.single));
    final two = geneva.firstWhere((d) => d.parcelles.length == 2 && d.parcelles.every(rich));
    // The one local dossier outside every parcel (a point in the public domain).
    final none = (await around(46.184026, 6.194948, 5)).firstWhere((d) => d.idDossier == 'DD 348411/1');
    expect(none.parcelles, isEmpty);
    expect(one.lienRegistreFoncier, registreFoncierUrl);

    Future<void> open(Dossier d, Brightness brightness) async {
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
    }

    Future<void> close() async {
      Navigator.of(tester.element(find.byType(BottomSheet))).pop();
      await tester.pumpAndSettle();
    }

    // Brings the "Parcelle(s)" header to the top of the sheet.
    Future<void> scrollToParcels() async {
      final header = find.text(find.text('Parcelles').evaluate().isNotEmpty ? 'Parcelles' : 'Parcelle');
      await Scrollable.ensureVisible(tester.element(header.first), alignment: 0.0);
      await tester.pumpAndSettle();
    }

    Future<void> expandAll() async {
      for (final tile in find.byType(ExpansionTile).evaluate().toList()) {
        await tester.ensureVisible(find.byWidget(tile.widget));
        await tester.tap(find.descendant(of: find.byWidget(tile.widget), matching: find.byType(ListTile)));
        await tester.pumpAndSettle();
      }
    }

    for (final brightness in Brightness.values) {
      for (final (name, d) in [('1-parcelle', one), ('2-parcelles', two), ('0-parcelle', none)]) {
        await open(d, brightness);
        if (d.parcelles.isEmpty) {
          expect(find.textContaining('Parcelle'), findsNothing);
          expect(find.byType(ExpansionTile), findsNothing);
          await tester.drag(find.byType(SingleChildScrollView).last, const Offset(0, -2000));
          await tester.pumpAndSettle();
          await shot('${brightness.name}-$name');
        } else {
          expect(find.text('Voir au registre foncier'), findsNWidgets(d.parcelles.length));
          expect(find.textContaining('EGRID'), findsNWidgets(d.parcelles.length));
          await scrollToParcels();
          await shot('${brightness.name}-$name-1-replie');
          await expandAll();
          await scrollToParcels();
          await shot('${brightness.name}-$name-2-deplie');
          await tester.drag(find.byType(SingleChildScrollView).last, const Offset(0, -420));
          await tester.pumpAndSettle();
          await shot('${brightness.name}-$name-3-deplie-suite');
        }
        await close();
      }
    }

    // The in-app browser: SFSafariViewController opens over the app and closes back to it. Whatever the page shows
    // (ge.ch may present a bot check) is only screenshotted, never touched.
    await open(one, Brightness.light);
    await scrollToParcels();
    await tester.ensureVisible(find.text('Voir au registre foncier'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Voir au registre foncier'));
    await tester.pump(const Duration(seconds: 1));
    await Future<void>.delayed(const Duration(seconds: 6)); // the sheet's slide-in and the page's first paint
    await shot('safari-1-ouvert');
    await Future<void>.delayed(const Duration(seconds: 4));
    await shot('safari-2-ouvert-apres-chargement');
    await closeInAppWebView();
    await Future<void>.delayed(const Duration(seconds: 2));
    await tester.pumpAndSettle();
    expect(find.text('Voir au registre foncier'), findsOneWidget, reason: 'back in the app after the sheet closed');
    await shot('safari-3-ferme-retour-app');
    await close();
    binding.reportData = {'checked': 'in-app browser opened and closed'};
  });
}
