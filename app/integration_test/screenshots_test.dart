// README screenshots, against the local backend with imported dossiers (dev profile: /admin/dev/emit fabricates
// a few events so the "Récent" feed is not empty). From app/, on a dev simulator (never the one holding a prod
// subscription), status bar optionally fixed with `xcrun simctl status_bar <id> override --time 9:41`:
//   flutter drive --driver=test_driver/screenshots.dart --target=integration_test/screenshots_test.dart -d <id>
import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:perimetre/api/models.dart';
import 'package:perimetre/api/perimetre_api.dart';
import 'package:perimetre/app.dart';
import 'package:perimetre/features/explore/widgets.dart';
import 'package:perimetre/features/subscription/providers.dart';
import 'package:perimetre/push/push_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Configured, so the "development mode" banner stays hidden.
class ScreenshotPushService implements PushService {
  @override
  bool get isConfigured => true;

  @override
  String get platform => 'ios';

  @override
  Future<PushPermission> permission() async => PushPermission.granted;

  @override
  Future<PushPermission> requestPermission() async => PushPermission.granted;

  @override
  Future<String?> currentToken() async => 'dev-token-screenshots';

  @override
  Stream<String> get onTokenRefresh => const Stream.empty();

  @override
  Stream<Map<String, dynamic>> get onOpened => const Stream.empty();

  @override
  Future<Map<String, dynamic>?> initialOpened() async => null;
}

Future<void> pumpFor(WidgetTester tester, Duration duration) async {
  final end = DateTime.now().add(duration);
  while (DateTime.now().isBefore(end)) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

Future<void> pumpUntil(WidgetTester tester, Finder finder, {Duration timeout = const Duration(seconds: 20)}) async {
  final end = DateTime.now().add(timeout);
  while (DateTime.now().isBefore(end)) {
    await tester.pump(const Duration(milliseconds: 100));
    if (finder.evaluate().isNotEmpty) return;
  }
  throw TestFailure('Timed out waiting for $finder');
}

void main() {
  final binding = IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  final dio = Dio(BaseOptions(baseUrl: apiBaseUrl));
  final api = PerimetreApi(dio);

  testWidgets('README screenshots', (tester) async {
    // Two perimeters on a fresh device, and a few fabricated events in them.
    final device = await api.createDevice(pushToken: 'dev-token-screenshots', platform: 'ios');
    final home = await api.createSubscription(
      device.id,
      const SubscriptionInput(
        lat: 46.19296,
        lng: 6.14123,
        radiusM: 300,
        categories: Groupe.notifiable,
        label: 'Domicile',
        address: 'Rue de Carouge 50 1205 Genève',
      ),
    );
    await api.createSubscription(
      device.id,
      const SubscriptionInput(
        lat: 46.20397,
        lng: 6.14513,
        radiusM: 500,
        categories: [Groupe.autorise, Groupe.chantier],
        label: 'Travail',
        address: 'Rue du Rhône 1 1204 Genève',
      ),
    );
    final nearHome = await api.dossiers(lat: home.lat, lng: home.lng, radius: home.radiusM);
    for (final (i, d) in nearHome.take(4).indexed) {
      await dio.post<void>(
        '/admin/dev/emit',
        queryParameters: {'id_dossier': d.idDossier, 'new_groupe': ['AUTORISE', 'CHANTIER', 'DEPOSE', 'RECOURS'][i]},
      );
    }

    final prefs = await SharedPreferences.getInstance();
    await prefs.clear();
    await prefs.setString(deviceIdKey, device.id);
    await tester.pumpWidget(
      ProviderScope(
        retry: (retryCount, error) => null,
        overrides: [
          sharedPreferencesProvider.overrideWithValue(prefs),
          pushServiceProvider.overrideWithValue(ScreenshotPushService()),
        ],
        child: const PerimetreApp(),
      ),
    );

    // Explorer around an address.
    await pumpUntil(tester, find.byType(AddressField));
    await tester.enterText(find.descendant(of: find.byType(AddressField), matching: find.byType(TextField)),
        'Rue de Carouge 50');
    final suggestion = find.textContaining('1205 Genève');
    await pumpUntil(tester, suggestion);
    await tester.tap(suggestion.first);
    await pumpUntil(tester, find.textContaining('dossiers dans un rayon'));
    await pumpFor(tester, const Duration(seconds: 4)); // map tiles
    await binding.takeScreenshot('explorer');

    // A dossier's details.
    await tester.tap(find.byType(DossierRow).first);
    await pumpUntil(tester, find.text('Voir le dossier officiel'));
    await pumpFor(tester, const Duration(seconds: 1));
    await binding.takeScreenshot('dossier');
    Navigator.of(tester.element(find.text('Voir le dossier officiel'))).pop();
    await pumpFor(tester, const Duration(milliseconds: 600));

    // Mes périmètres: two perimeters and the feed.
    await tester.tap(find.descendant(of: find.byType(NavigationBar), matching: find.text('Mes périmètres')));
    await pumpUntil(tester, find.text('Travail'));
    await pumpUntil(tester, find.byType(DossierRow));
    await pumpFor(tester, const Duration(seconds: 1));
    await binding.takeScreenshot('perimetres');

    // The add form.
    await tester.tap(find.text('Ajouter un périmètre'));
    await pumpUntil(tester, find.text('Nouveau périmètre'));
    await tester.tap(find.widgetWithText(ChoiceChip, 'Travail'));
    await pumpFor(tester, const Duration(seconds: 1));
    await binding.takeScreenshot('nouveau-perimetre');

    await dio.delete<void>('/devices/${device.id}');
  });
}
