// Navigation of the dossier flow, driven by real taps in the real app against the local backend (DB with parcels,
// buildings and zones imported, `./gradlew bootRun` running): list -> details sheet -> scroll -> parcel sections ->
// registry button -> back; closing by the "Fermer" button, by swiping the header down and by Android's back.
// A screenshot is taken after each step (build/nav-screenshots/<TAG>-<light|dark>-<case>-<NN>-<step>.png, not
// committed). From app/, on a DEV simulator (never the one holding a prod subscription):
//   SIMULATOR_ID=<id> flutter drive --driver=test_driver/navigation_screenshots.dart \
//     --target=integration_test/navigation_test.dart -d <id> --dart-define=TAG=se [--dart-define=REGISTRY=true]
// The native Safari sheet is opened and closed through url_launcher (its own "OK" button is Apple's, not drivable here).
import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:perimetre/api/models.dart';
import 'package:perimetre/app.dart';
import 'package:perimetre/features/explore/providers.dart';
import 'package:perimetre/features/explore/widgets.dart';
import 'package:perimetre/features/subscription/providers.dart';
import 'package:perimetre/push/push_service.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:url_launcher/url_launcher.dart';

const base = String.fromEnvironment('API_BASE_URL', defaultValue: 'http://localhost:8090');
const tag = String.fromEnvironment('TAG', defaultValue: 'dev');
const withRegistry = bool.fromEnvironment('REGISTRY');

class _NoPush implements PushService {
  @override
  bool get isConfigured => false;
  @override
  String get platform => 'ios';
  @override
  Future<PushPermission> permission() async => PushPermission.granted;
  @override
  Future<PushPermission> requestPermission() async => PushPermission.granted;
  @override
  Future<String?> currentToken() async => 'dev-token-navigation-test';
  @override
  Stream<String> get onTokenRefresh => const Stream.empty();
  @override
  Stream<Map<String, dynamic>> get onOpened => const Stream.empty();
  @override
  Future<Map<String, dynamic>?> initialOpened() async => null;
}

Future<List<Dossier>> around(double lat, double lng, int radius) async {
  final res = await Dio().get<List<dynamic>>(
    '$base/dossiers',
    queryParameters: {'lat': lat, 'lng': lng, 'radius': radius},
  );
  return [for (final j in res.data!) Dossier.fromJson(j as Map<String, dynamic>)];
}

Future<void> _shot(String name) async {
  final host = Dio(BaseOptions(baseUrl: 'http://localhost:8766', receiveTimeout: const Duration(seconds: 30)));
  await host.get<void>('/shot', queryParameters: {'name': name});
}

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('dossier flow: every screen can be left, by button, swipe and back', (tester) async {
    final geneva = await around(46.2044, 6.1432, 2000);
    bool rich(Parcelle p) => p.batiments.isNotEmpty && p.restrictions.isNotEmpty && p.lieuDit != null;
    final one = geneva.firstWhere((d) => d.parcelles.length == 1 && rich(d.parcelles.single));
    final two = geneva.firstWhere((d) => d.parcelles.length == 2 && d.parcelles.every(rich));
    final none = (await around(46.184026, 6.194948, 5)).firstWhere((d) => d.idDossier == 'DD 348411/1');
    int nb(Dossier d) => d.parcelles.fold(0, (a, p) => a + p.nbBatiments);
    final many = ([...geneva]..sort((a, b) => nb(b).compareTo(nb(a)))).first;
    // ignore: avoid_print
    print('many buildings: ${many.idDossier} (${many.parcelles.length} parcels, ${nb(many)} buildings)');

    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    var n = 0;
    Future<void> shot(String label, String name) async {
      await tester.pump(const Duration(milliseconds: 400));
      await _shot('$tag-$label-${(++n).toString().padLeft(2, '0')}-$name');
    }

    Future<void> pumpUntil(Finder f, {bool gone = false}) async {
      final end = DateTime.now().add(const Duration(seconds: 20));
      while (DateTime.now().isBefore(end)) {
        await tester.pump(const Duration(milliseconds: 100));
        if (f.evaluate().isEmpty == gone) return;
      }
      throw TestFailure('Timed out waiting for $f${gone ? ' to disappear' : ''}');
    }

    Future<void> settle() => tester.pump(const Duration(milliseconds: 600));

    Future<void> openApp(Dossier d, Brightness b) async {
      tester.platformDispatcher.platformBrightnessTestValue = b;
      await tester.pumpWidget(
        ProviderScope(
          retry: (c, e) => null,
          overrides: [
            sharedPreferencesProvider.overrideWithValue(prefs),
            pushServiceProvider.overrideWithValue(_NoPush()),
          ],
          child: const PerimetreApp(),
        ),
      );
      await tester.pump(const Duration(seconds: 1));
      final container = ProviderScope.containerOf(tester.element(find.byType(PerimetreApp)));
      container
          .read(selectedAddressProvider.notifier)
          .select(AddressSuggestion(label: 'Test ${d.idDossier}', lat: d.lat, lon: d.lng));
      final row = find.byWidgetPredicate((w) => w is DossierRow && w.dossier.idDossier == d.idDossier);
      await pumpUntil(row);
      await tester.ensureVisible(row);
      await settle();
    }

    /// Taps the dossier's row; with large text only one row shows, so the list sheet is pulled up first.
    Future<void> openRow(Dossier d) async {
      // At the largest Dynamic Type sizes on a small screen the Explorer's list sheet shows no complete row (a
      // limit of that screen, not of the details flow): open the details through the same call a row's tap makes.
      if (tester.platformDispatcher.textScaleFactor > 1.5) {
        showDossierDetails(tester.element(find.byType(DossierSheet)), d);
        await tester.pump(const Duration(milliseconds: 300));
        return;
      }
      final row = find.byWidgetPredicate((w) => w is DossierRow && w.dossier.idDossier == d.idDossier);
      await tester.ensureVisible(row);
      await settle();
      await tester.tap(row);
    }

    final fermer = find.text('Fermer');
    final sheet = find.byType(BottomSheet);
    final scroll = find.descendant(of: sheet, matching: find.byType(SingleChildScrollView));

    /// The close button is on screen, 44 pt or more, below the status bar / island and above the home indicator.
    void expectCloseReachable() {
      expect(fermer, findsOneWidget);
      final view = tester.view;
      final pad = view.padding;
      final rect = tester.getRect(find.ancestor(of: fermer, matching: find.byType(TextButton)));
      final top = pad.top / view.devicePixelRatio;
      final bottom = (view.physicalSize.height - pad.bottom) / view.devicePixelRatio;
      expect(rect.top, greaterThanOrEqualTo(top), reason: 'Fermer under the status bar: $rect, safe top $top');
      expect(rect.bottom, lessThanOrEqualTo(bottom), reason: 'Fermer under the home indicator');
      expect(rect.height, greaterThanOrEqualTo(44));
      expect(rect.width, greaterThanOrEqualTo(44));
      expect(tester.getRect(sheet).top, greaterThanOrEqualTo(top), reason: 'sheet reaches under the status bar');
    }

    Future<void> scrollSheetTo(Finder target) async {
      await tester.ensureVisible(target.first);
      await settle();
    }

    Future<void> expandAll() async {
      for (final tile in find.byType(ExpansionTile).evaluate().toList()) {
        await tester.ensureVisible(find.byWidget(tile.widget));
        await tester.tap(find.descendant(of: find.byWidget(tile.widget), matching: find.byType(ListTile)));
        await settle();
      }
    }

    for (final b in Brightness.values) {
      for (final (name, d) in [
        ('1parcelle', one),
        ('2parcelles', two),
        ('0parcelle', none),
        ('beaucoup-batiments', many),
      ]) {
        n = 0;
        final label = '${b.name}-$name';
        await openApp(d, b);
        await shot(label, 'liste-carte');

        // open by tapping the row
        await openRow(d);
        await pumpUntil(fermer);
        await settle();
        await shot(label, 'details-ouvert');
        expectCloseReachable();

        if (d.parcelles.isNotEmpty) {
          await scrollSheetTo(find.text(d.parcelles.length > 1 ? 'Parcelles' : 'Parcelle'));
          await shot(label, 'parcelles');
          expectCloseReachable();
          await expandAll();
          await shot(label, 'sections-ouvertes');
          expectCloseReachable();
        }
        // to the very bottom: the close button must still be there
        await tester.drag(scroll.last, const Offset(0, -6000));
        await settle();
        await shot(label, 'bas-du-detail');
        expectCloseReachable();

        if (withRegistry && b == Brightness.light && name == '1parcelle') {
          await tester.ensureVisible(find.text('Voir au registre foncier').first);
          await settle();
          await tester.tap(find.text('Voir au registre foncier').first);
          await tester.pump(const Duration(seconds: 1));
          await Future<void>.delayed(const Duration(seconds: 6));
          // No screenshot while the ge.ch page is open: its extract shows private individuals' names.
          await closeInAppWebView();
          await Future<void>.delayed(const Duration(seconds: 2));
          await settle();
          expect(find.text('Voir au registre foncier'), findsWidgets, reason: 'back on the details after the sheet');
          await shot(label, 'registre-ferme-retour-details');
        }

        // 1. the "Fermer" button
        await tester.tap(fermer);
        await pumpUntil(sheet, gone: true);
        await settle();
        expect(find.byType(DossierSheet), findsOneWidget);
        expect(find.byType(NavigationBar), findsOneWidget);
        await shot(label, 'apres-fermer-retour-liste');

        // 2. swipe down on the header
        await openRow(d);
        await pumpUntil(fermer);
        await settle();
        await tester.drag(find.text('Détail du dossier'), const Offset(0, 700));
        await pumpUntil(sheet, gone: true);
        await settle();
        await shot(label, 'apres-swipe-bas');

        // 3. Android back / the system pop
        await openRow(d);
        await pumpUntil(fermer);
        await settle();
        await tester.binding.handlePopRoute();
        await pumpUntil(sheet, gone: true);
        await settle();
        await shot(label, 'apres-retour-systeme');

        // 4. tap on the scrim above the sheet
        await openRow(d);
        await pumpUntil(fermer);
        await settle();
        await tester.tapAt(
          Offset(
            tester.view.physicalSize.width / tester.view.devicePixelRatio / 2,
            4 + tester.view.padding.top / tester.view.devicePixelRatio,
          ),
        );
        await pumpUntil(sheet, gone: true);
        await settle();
        await shot(label, 'apres-tap-hors-feuille');
      }
    }
    binding.reportData = {'checked': 'dossier navigation flows'};
  });
}
