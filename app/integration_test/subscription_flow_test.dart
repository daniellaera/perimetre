// End-to-end on a simulator against the local backend (DB imported, `./gradlew bootRun` running):
//   flutter test integration_test -d "iPhone 17 Pro"
// Uses the real geocoder and backend; push delivery is replaced by a fake that can simulate a tap.
import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:perimetre/api/perimetre_api.dart';
import 'package:perimetre/app.dart';
import 'package:perimetre/features/subscription/providers.dart';
import 'package:perimetre/push/push_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

class FakePushService implements PushService {
  final taps = StreamController<Map<String, dynamic>>.broadcast();

  @override
  bool get isConfigured => false;

  @override
  String get platform => 'ios';

  @override
  Future<bool> requestPermission() async => true;

  @override
  Future<String> getToken() async => 'dev-token-integration-test';

  @override
  Stream<String> get onTokenRefresh => const Stream.empty();

  @override
  Stream<Map<String, dynamic>> get onOpened => taps.stream;

  @override
  Future<Map<String, dynamic>?> initialOpened() async => null;
}

/// Pumps frames until [finder] matches (pumpAndSettle never settles while tiles and spinners animate).
Future<void> pumpUntil(WidgetTester tester, Finder finder, {Duration timeout = const Duration(seconds: 15)}) async {
  final end = DateTime.now().add(timeout);
  while (DateTime.now().isBefore(end)) {
    await tester.pump(const Duration(milliseconds: 100));
    if (finder.evaluate().isNotEmpty) return;
  }
  throw TestFailure('Timed out waiting for $finder');
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('subscribe, open a pushed dossier, deactivate', (tester) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.clear();
    final push = FakePushService();
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
    await pumpUntil(tester, find.text('Mon périmètre'));

    await tester.tap(find.text('Mon périmètre'));
    await pumpUntil(tester, find.text('Activer les notifications'));

    await tester.enterText(find.byType(TextField), 'Rue de Carouge 50');
    final suggestion = find.textContaining('1205 Genève');
    await pumpUntil(tester, suggestion);
    await tester.tap(suggestion.first);
    await tester.pump(const Duration(milliseconds: 300));
    await tester.tap(find.text('Activer les notifications'));
    await pumpUntil(tester, find.text('Notifications actives'));

    final id = prefs.getString('subscription_id');
    expect(id, isNotNull);
    await pumpUntil(tester, find.text('Récent'));

    // Simulate tapping a push about a dossier inside the perimeter.
    final api = PerimetreApi(Dio(BaseOptions(baseUrl: apiBaseUrl)));
    final subscription = (await api.subscription(id!))!;
    expect(subscription.radiusM, 200);
    final nearby = await api.dossiers(lat: subscription.lat, lng: subscription.lng, radius: subscription.radiusM);
    expect(nearby, isNotEmpty);
    final target = nearby.first;
    push.taps.add({'id_dossier': target.idDossier, 'kind': 'GROUPE_CHANGE', 'new_groupe': 'CHANTIER'});
    await pumpUntil(tester, find.text('Voir le dossier officiel'));
    expect(find.text(target.idDossier), findsOneWidget);
    // "Demande" dossiers use their description as the sheet title, without a "Demande" heading.
    if (target.operationLabel == 'Demande' && target.description != null) {
      expect(find.text('Demande'), findsNothing);
      expect(find.text(target.description!), findsOneWidget);
    }
    Navigator.of(tester.element(find.text('Voir le dossier officiel'))).pop();
    await tester.pump(const Duration(milliseconds: 500));

    // A push about an unknown dossier shows a plain message.
    push.taps.add({'id_dossier': 'XX 000000/0', 'kind': 'NEW', 'new_groupe': 'DEPOSE'});
    await pumpUntil(tester, find.text('Ce dossier n\'est plus disponible.'));

    await tester.tap(find.text('Désactiver'));
    await pumpUntil(tester, find.widgetWithText(TextButton, 'Désactiver').last);
    await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('Désactiver')));
    await pumpUntil(tester, find.text('Activer les notifications'));
    expect(prefs.getString('subscription_id'), isNull);
    expect(await api.subscription(id), isNull);
  });
}
