// End-to-end on a simulator against the local backend (DB imported, `./gradlew bootRun` running):
//   flutter test integration_test -d "<dev simulator>"   (never the simulator holding the prod subscription)
// Uses the real geocoder and backend; push delivery is replaced by a fake that can simulate a tap.
import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:perimetre/api/perimetre_api.dart';
import 'package:perimetre/app.dart';
import 'package:perimetre/features/explore/widgets.dart';
import 'package:perimetre/features/subscription/providers.dart';
import 'package:perimetre/push/push_service.dart';
import 'package:perimetre/push/token_sync.dart';
import 'package:shared_preferences/shared_preferences.dart';

class FakePushService implements PushService {
  final taps = StreamController<Map<String, dynamic>>.broadcast();

  /// The OS permission, and what the OS prompt turns a not-determined one into.
  PushPermission current = PushPermission.granted;
  PushPermission onPrompt = PushPermission.granted;

  @override
  bool get isConfigured => false;

  @override
  String get platform => 'ios';

  @override
  Future<PushPermission> permission() async => current;

  @override
  Future<PushPermission> requestPermission() async =>
      current = current == PushPermission.notDetermined ? onPrompt : current;

  @override
  Future<String?> currentToken() async => 'dev-token-integration-test';

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
  final texts = find.byType(Text).evaluate().map((e) => (e.widget as Text).data).nonNulls.join(' | ');
  throw TestFailure('Timed out waiting for $finder. On screen: $texts');
}

Future<void> pumpUntilGone(WidgetTester tester, Finder finder, {Duration timeout = const Duration(seconds: 15)}) async {
  final end = DateTime.now().add(timeout);
  while (DateTime.now().isBefore(end)) {
    await tester.pump(const Duration(milliseconds: 100));
    if (finder.evaluate().isEmpty) return;
  }
  throw TestFailure('Timed out waiting for $finder to disappear');
}

Future<void> pumpApp(WidgetTester tester, SharedPreferences prefs, PushService push) async {
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
  final tab = find.descendant(of: find.byType(NavigationBar), matching: find.text('Mes périmètres'));
  await pumpUntil(tester, tab);
  await tester.tap(tab);
}

/// Types [query] in the form's address field and picks the first suggestion containing [locality].
Future<void> pickAddress(WidgetTester tester, String query, String locality) async {
  await tester.enterText(find.descendant(of: find.byType(AddressField), matching: find.byType(TextField)), query);
  final suggestion = find.textContaining(locality);
  await pumpUntil(tester, suggestion);
  await tester.tap(suggestion.first);
  await tester.pump(const Duration(milliseconds: 300));
}

Future<void> deleteFromCard(WidgetTester tester, String name) async {
  final card = find.ancestor(of: find.text(name), matching: find.byType(Card));
  await tester.tap(find.descendant(of: card, matching: find.byTooltip('Actions')));
  final menuItem = find.widgetWithText(PopupMenuItem<String>, 'Supprimer');
  await pumpUntil(tester, menuItem);
  await tester.pump(const Duration(milliseconds: 500)); // menu opening animation
  await tester.tap(menuItem);
  await pumpUntil(tester, find.byType(AlertDialog));
  await tester.tap(find.descendant(of: find.byType(AlertDialog), matching: find.text('Supprimer')));
  await pumpUntilGone(tester, card);
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  final api = PerimetreApi(Dio(BaseOptions(baseUrl: apiBaseUrl)));

  testWidgets('add two perimeters, open a pushed dossier, delete them', (tester) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.clear();
    final push = FakePushService();
    await pumpApp(tester, prefs, push);
    await pumpUntil(tester, find.text('Activer les notifications'));

    // First perimeter: the form fills the tab.
    await tester.tap(find.widgetWithText(ChoiceChip, 'Domicile'));
    await pickAddress(tester, 'Rue de Carouge 50', '1205 Genève');
    await tester.tap(find.text('Activer les notifications'));
    await pumpUntil(tester, find.text('Ajouter un périmètre'));
    expect(find.text('Domicile'), findsOneWidget);
    final deviceId = prefs.getString(deviceIdKey);
    expect(deviceId, isNotNull);

    // Second perimeter, through the full-screen form.
    await tester.tap(find.text('Ajouter un périmètre'));
    await pumpUntil(tester, find.text('Nouveau périmètre'));
    await tester.tap(find.widgetWithText(ChoiceChip, 'Travail'));
    await pickAddress(tester, 'Rue du Rhône 1', '1204 Genève');
    await tester.tap(find.text('Ajouter ce périmètre'));
    await pumpUntilGone(tester, find.text('Nouveau périmètre'));
    await pumpUntil(tester, find.text('Travail'));
    final subscriptions = (await api.subscriptions(deviceId!))!;
    expect(subscriptions.map((s) => s.label), ['Domicile', 'Travail']);
    expect(subscriptions.last.address, contains('1204 Genève'));
    expect(subscriptions.first.radiusM, 200);
    await pumpUntil(tester, find.text('Récent'));

    // Simulate tapping a push about a dossier inside the second perimeter.
    final work = subscriptions.last;
    final nearby = await api.dossiers(lat: work.lat, lng: work.lng, radius: work.radiusM);
    expect(nearby, isNotEmpty);
    final target = nearby.first;
    push.taps.add({
      'id_dossier': target.idDossier,
      'kind': 'GROUPE_CHANGE',
      'new_groupe': 'CHANTIER',
      'subscription_id': work.id,
    });
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

    await deleteFromCard(tester, 'Travail');
    expect(find.text('Domicile'), findsOneWidget);
    await deleteFromCard(tester, 'Domicile');
    await pumpUntil(tester, find.text('Activer les notifications'));
    expect(await api.subscriptions(deviceId), isEmpty);
  });

  testWidgets('explains before the OS prompt, flags a refusal on each perimeter, sends a test push', (tester) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.clear();
    final push = FakePushService()
      ..current = PushPermission.notDetermined
      ..onPrompt = PushPermission.denied;
    await pumpApp(tester, prefs, push);
    await pumpUntil(tester, find.text('Activer les notifications'));

    // The perimeter is saved first, then the explanation page, then the (fake) OS prompt, refused.
    await pickAddress(tester, 'Rue de Carouge 50', '1205 Genève');
    await tester.tap(find.text('Activer les notifications'));
    await pumpUntil(tester, find.text('Recevoir les alertes'));
    expect(prefs.getString(deviceIdKey), isNotNull);
    await tester.pump(const Duration(milliseconds: 500)); // page transition
    await tester.tap(find.text('Continuer'));
    await pumpUntilGone(tester, find.text('Recevoir les alertes'));
    await pumpUntil(tester, find.text('Notifications désactivées : aucune alerte n\'arrivera.'));
    expect(find.text('Ouvrir les Réglages'), findsOneWidget);
    expect(prefs.getString(TokenSync.sentTokenKey), 'dev-token-integration-test');

    // A test push would not show: the app says so instead of sending it.
    await tester.tap(find.text('Tester les notifications'));
    await pumpUntil(tester, find.textContaining('la notification de test ne s\'afficherait pas'));
    await tester.pump(const Duration(seconds: 5)); // let the SnackBar go

    // Allowed in the Settings app, back to the app: the notice goes, the test push is sent, then rate-limited.
    push.current = PushPermission.granted;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.inactive);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await pumpUntilGone(tester, find.text('Ouvrir les Réglages'));
    await tester.tap(find.text('Tester les notifications'));
    await pumpUntil(tester, find.textContaining('Notification de test envoyée'));
    await tester.pump(const Duration(seconds: 5));
    await tester.tap(find.text('Tester les notifications'));
    await pumpUntil(tester, find.text('Une notification de test vient d\'être envoyée. Réessayez dans une minute.'));

    await deleteFromCard(tester, 'Rue de Carouge 50');
    await pumpUntil(tester, find.text('Activer les notifications'));
  });

  testWidgets('adopts the subscription of the single-subscription app build', (tester) async {
    // What the first app build did: POST /subscriptions with the address as label, id kept in prefs.
    final legacy = await Dio(BaseOptions(baseUrl: apiBaseUrl)).post<Map<String, dynamic>>(
      '/subscriptions',
      data: {
        'push_token': 'dev-token-integration-legacy',
        'platform': 'ios',
        'lat': 46.1934,
        'lng': 6.1425,
        'radius_m': 300,
        'label': 'Rue de Carouge 50 1205 Genève',
      },
    );
    final legacyId = legacy.data!['id'] as String;
    final prefs = await SharedPreferences.getInstance();
    await prefs.clear();
    await prefs.setString(legacySubscriptionIdKey, legacyId);

    await pumpApp(tester, prefs, FakePushService());
    await pumpUntil(tester, find.text('Rue de Carouge 50'));
    expect(prefs.getString(deviceIdKey), legacyId);
    expect(prefs.getString(legacySubscriptionIdKey), isNull);
    expect(find.text('Rayon 300 m · tous les statuts'), findsOneWidget);

    await deleteFromCard(tester, 'Rue de Carouge 50');
    await pumpUntil(tester, find.text('Activer les notifications'));
  });
}
