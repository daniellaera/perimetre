// App Store screenshots (6.9" iPhone, 1320 × 2868), against the local backend with freshly imported dossiers
// (`POST /admin/import?mode=full` first, so the "Récent" feed shows real recent events). The host takes each
// picture with `xcrun simctl io` (status bar included), see test_driver/store_screenshots.dart. From app/, on
// the dedicated "Perimetre Store (iPhone 17 Pro Max)" simulator (never one holding a prod subscription):
//   SIMULATOR_ID=<id> flutter drive --driver=test_driver/store_screenshots.dart \
//     --target=integration_test/store_screenshots_test.dart --dart-define-from-file=config/dev.json -d <id>
// Screenshots land in docs/app-store/.
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

/// Configured (no "development mode" banner); the permission can be switched to "not determined" so the
/// explanation page shows after a new perimeter.
class StorePushService implements PushService {
  PushPermission state = PushPermission.granted;

  @override
  bool get isConfigured => true;

  @override
  String get platform => 'ios';

  @override
  Future<PushPermission> permission() async => state;

  @override
  Future<PushPermission> requestPermission() async => state = PushPermission.granted;

  @override
  Future<String?> currentToken() async => 'dev-token-store-screenshots';

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

/// Asks the driver's server on the Mac to screenshot the simulator. The frame stays as pumped meanwhile (the
/// binding only draws on pumps). Retried while the driver is still starting.
Future<void> shot(String name) async {
  final host = Dio(BaseOptions(baseUrl: 'http://localhost:8765', receiveTimeout: const Duration(seconds: 30)));
  final end = DateTime.now().add(const Duration(seconds: 60));
  while (true) {
    try {
      await host.get<void>('/shot', queryParameters: {'name': name});
      return;
    } on DioException catch (e) {
      if (e.type != DioExceptionType.connectionError || DateTime.now().isAfter(end)) rethrow;
      await Future<void>.delayed(const Duration(seconds: 1));
    }
  }
}

Future<void> typeAddress(WidgetTester tester, Finder field, String text, String pick) async {
  await tester.enterText(find.descendant(of: field, matching: find.byType(TextField)), text);
  final suggestion = find.textContaining(pick);
  await pumpUntil(tester, suggestion);
  await tester.tap(suggestion.first);
  await tester.pump();
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  final dio = Dio(BaseOptions(baseUrl: apiBaseUrl));
  final api = PerimetreApi(dio);

  testWidgets('App Store screenshots', (tester) async {
    // Two perimeters in the old town, where SITG's last days have real events.
    final device = await api.createDevice(pushToken: 'dev-token-store-screenshots', platform: 'ios');
    await api.createSubscription(
      device.id,
      const SubscriptionInput(
        lat: 46.20205,
        lng: 6.15182,
        radiusM: 400,
        categories: Groupe.notifiable,
        label: 'Domicile',
        address: 'Rue de Rive 15 1204 Genève',
      ),
    );
    await api.createSubscription(
      device.id,
      const SubscriptionInput(
        lat: 46.20448,
        lng: 6.14403,
        radiusM: 300,
        categories: [Groupe.depose, Groupe.autorise, Groupe.chantier],
        label: 'Travail',
        address: 'Rue du Rhône 1 1204 Genève',
      ),
    );

    final prefs = await SharedPreferences.getInstance();
    await prefs.clear();
    await prefs.setString(deviceIdKey, device.id);
    final push = StorePushService();
    await tester.pumpWidget(
      ProviderScope(
        retry: (retryCount, error) => null,
        overrides: [
          sharedPreferencesProvider.overrideWithValue(prefs),
          pushServiceProvider.overrideWithValue(push),
        ],
        child: const PerimetreApp(),
      ),
    );

    try {
      // 1. Explorer: the map around an address.
      await pumpUntil(tester, find.byType(AddressField));
      await typeAddress(tester, find.byType(AddressField), 'Rue de Rive 15', 'Rive 15 1204 Genève');
      await pumpUntil(tester, find.textContaining('dossiers dans un rayon'));
      await pumpFor(tester, const Duration(seconds: 5)); // map tiles
      await shot('1-explorer');

      // 2. Mes périmètres: two perimeters and the feed of recent alerts.
      await tester.tap(find.descendant(of: find.byType(NavigationBar), matching: find.text('Mes périmètres')));
      await pumpUntil(tester, find.text('Travail'));
      await pumpUntil(tester, find.byType(DossierRow));
      await pumpFor(tester, const Duration(seconds: 1));
      await shot('2-perimetres');

      // 3. An alert's details.
      final alert = find.byType(DossierRow).first;
      await tester.ensureVisible(alert);
      await tester.tap(alert);
      await pumpUntil(tester, find.text('Voir le dossier officiel'));
      await pumpFor(tester, const Duration(seconds: 1));
      await shot('3-alerte');
      Navigator.of(tester.element(find.text('Voir le dossier officiel'))).pop();
      await pumpFor(tester, const Duration(milliseconds: 600));

      // 4. A new perimeter, filled in.
      // Opening the alert scrolled the list; back to the top, where the button is built again.
      await tester.fling(find.byType(DossierRow).hitTestable().first, const Offset(0, 3000), 5000);
      await pumpFor(tester, const Duration(seconds: 1));
      await tester.tap(find.text('Ajouter un périmètre'));
      await pumpUntil(tester, find.text('Nouveau périmètre'));
      await tester.enterText(find.widgetWithText(TextField, 'Nom (facultatif)'), 'Parents');
      final form = find.ancestor(of: find.text('Nouveau périmètre'), matching: find.byType(Scaffold)).first;
      await typeAddress(
        tester,
        find.descendant(of: form, matching: find.byType(AddressField)),
        'Avenue de Champel 24',
        'Champel 24 1206 Genève',
      );
      FocusManager.instance.primaryFocus?.unfocus();
      await pumpFor(tester, const Duration(seconds: 1));
      await shot('4-nouveau-perimetre');

      // 5. The explanation shown before the iOS permission prompt.
      push.state = PushPermission.notDetermined;
      final save = find.text('Ajouter ce périmètre');
      await tester.ensureVisible(save);
      await tester.tap(save);
      await pumpUntil(tester, find.text('Recevoir les alertes'));
      await pumpFor(tester, const Duration(seconds: 1));
      await shot('5-notifications');
      await tester.tap(find.text('Continuer'));
      await pumpFor(tester, const Duration(seconds: 1));
    } finally {
      await dio.delete<void>('/devices/${device.id}');
    }
  });
}
