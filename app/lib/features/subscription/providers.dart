import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../api/models.dart';
import '../../api/perimetre_api.dart';
import '../../push/push_service.dart';
import '../explore/providers.dart';

part 'providers.g.dart';

/// Overridden in main() with the instance loaded before runApp.
@Riverpod(keepAlive: true)
SharedPreferences sharedPreferences(Ref ref) => throw UnimplementedError('sharedPreferencesProvider must be overridden');

/// Thrown by [MySubscription.save] when the user refuses notifications.
class NotificationsDeniedException implements Exception {
  const NotificationsDeniedException();
}

/// The device's subscription. Its id (the only handle on it: there is no account) lives in shared_preferences.
@Riverpod(keepAlive: true)
class MySubscription extends _$MySubscription {
  static const _idKey = 'subscription_id';

  SharedPreferences get _prefs => ref.read(sharedPreferencesProvider);
  PerimetreApi get _api => ref.read(perimetreApiProvider);
  PushService get _push => ref.read(pushServiceProvider);

  @override
  Future<Subscription?> build() async {
    final tokenRefresh = _push.onTokenRefresh.listen(_onTokenRefresh);
    ref.onDispose(tokenRefresh.cancel);

    final id = _prefs.getString(_idKey);
    if (id == null) return null;
    final subscription = await _api.subscription(id);
    // Deleted server-side (e.g. after repeated invalid tokens): forget it. Network errors keep the id.
    if (subscription == null) await _prefs.remove(_idKey);
    return subscription;
  }

  /// Asks for notification permission, gets the push token, then creates or updates the subscription.
  Future<void> save({
    required AddressSuggestion address,
    required int radiusM,
    required List<Groupe> categories,
  }) async {
    if (!await _push.requestPermission()) throw const NotificationsDeniedException();
    final input = SubscriptionInput(
      pushToken: await _push.getToken(),
      platform: _push.platform,
      lat: address.lat,
      lng: address.lon,
      radiusM: radiusM,
      categories: categories,
      label: address.label,
    );
    final current = state.value;
    // An update can find the subscription gone (deleted server-side): create a new one then.
    final saved = (current == null ? null : await _api.updateSubscription(current.id, input)) ??
        await _api.createSubscription(input);
    await _prefs.setString(_idKey, saved.id);
    state = AsyncData(saved);
  }

  Future<void> deactivate() async {
    final current = state.value;
    if (current == null) return;
    await _api.deleteSubscription(current.id);
    await _prefs.remove(_idKey);
    state = const AsyncData(null);
  }

  Future<void> _onTokenRefresh(String token) async {
    final current = state.value;
    if (current == null) return;
    try {
      final updated = await _api.updateSubscription(
        current.id,
        SubscriptionInput(
          pushToken: token,
          platform: _push.platform,
          lat: current.lat,
          lng: current.lng,
          radiusM: current.radiusM,
          categories: current.categories,
          label: current.label,
        ),
      );
      if (updated != null) state = AsyncData(updated);
    } catch (e) {
      // Retried on the next refresh or the next save; the old token keeps working for a while.
      debugPrint('Push token refresh not sent to the backend: $e');
    }
  }
}

/// Recent events in the subscription's perimeter, newest first.
@riverpod
Future<List<DossierEvent>> recentEvents(Ref ref) async {
  final subscription = await ref.watch(mySubscriptionProvider.future);
  if (subscription == null) return const [];
  return ref.watch(perimetreApiProvider).subscriptionEvents(subscription.id);
}

/// Finds the dossier of a tapped push around the subscription point: /dossiers first, then the event feed,
/// since /dossiers is capped at 200 results and hides TERMINE dossiers. Null when not found.
Future<Dossier?> findPushedDossier(PerimetreApi api, Subscription subscription, String idDossier) async {
  final around = await api.dossiers(lat: subscription.lat, lng: subscription.lng, radius: subscription.radiusM);
  for (final d in around) {
    if (d.idDossier == idDossier) return d;
  }
  final events = await api.subscriptionEvents(subscription.id);
  for (final e in events) {
    if (e.dossier.idDossier == idDossier) return e.dossier;
  }
  return null;
}
