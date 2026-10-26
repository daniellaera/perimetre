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

/// Thrown by [MySubscriptions.add] and [MySubscriptions.edit] when the user refuses notifications.
class NotificationsDeniedException implements Exception {
  const NotificationsDeniedException();
}

/// Keys in shared_preferences. The device id is the only handle on the device's subscriptions (no account).
const deviceIdKey = 'device_id';

/// The single-subscription app build stored its subscription id here. The backend gave that subscription a
/// device with the same id, so it is adopted as the device id.
const legacySubscriptionIdKey = 'subscription_id';

/// The device's subscriptions (perimeters), oldest first. Empty until the first one is added.
@Riverpod(keepAlive: true)
class MySubscriptions extends _$MySubscriptions {
  SharedPreferences get _prefs => ref.read(sharedPreferencesProvider);
  PerimetreApi get _api => ref.read(perimetreApiProvider);
  PushService get _push => ref.read(pushServiceProvider);

  /// Null until the first subscription is added (or after the backend forgot the device).
  String? get deviceId => _prefs.getString(deviceIdKey);

  @override
  Future<List<Subscription>> build() async {
    final tokenRefresh = _push.onTokenRefresh.listen(_onTokenRefresh);
    ref.onDispose(tokenRefresh.cancel);

    await _adoptLegacySubscription();
    final id = deviceId;
    if (id == null) return const [];
    final subscriptions = await _api.subscriptions(id);
    // Deleted server-side (e.g. after repeated invalid tokens): forget it. Network errors keep the id.
    if (subscriptions == null) await _prefs.remove(deviceIdKey);
    return subscriptions ?? const [];
  }

  Future<void> _adoptLegacySubscription() async {
    final legacyId = _prefs.getString(legacySubscriptionIdKey);
    if (legacyId == null) return;
    if (deviceId == null) await _prefs.setString(deviceIdKey, legacyId);
    await _prefs.remove(legacySubscriptionIdKey);
  }

  /// Asks for notification permission, registers the device (or refreshes its push token), then creates the
  /// subscription. Throws [SubscriptionLimitException] at the backend's cap.
  Future<Subscription> add(SubscriptionInput input) async {
    final device = await _ensureDevice();
    final created = await _api.createSubscription(device, input);
    state = AsyncData([...?state.value, created]);
    return created;
  }

  /// Replaces every field. Recreates the subscription if the backend no longer has it.
  Future<Subscription> edit(String id, SubscriptionInput input) async {
    final device = await _ensureDevice();
    final saved = await _api.updateSubscription(device, id, input) ?? await _api.createSubscription(device, input);
    state = AsyncData([
      for (final s in state.value ?? const <Subscription>[]) s.id == id ? saved : s,
    ]);
    return saved;
  }

  Future<void> remove(String id) async {
    final device = deviceId;
    if (device != null) await _api.deleteSubscription(device, id);
    state = AsyncData([
      for (final s in state.value ?? const <Subscription>[])
        if (s.id != id) s,
    ]);
  }

  /// The device id, after sending the current push token: a new device when there is none yet or the
  /// backend no longer has it (its subscriptions are then gone too).
  Future<String> _ensureDevice() async {
    if (!await _push.requestPermission()) throw const NotificationsDeniedException();
    final token = await _push.getToken();
    final current = deviceId;
    if (current != null && await _api.updateDevice(current, pushToken: token, platform: _push.platform) != null) {
      return current;
    }
    final created = await _api.createDevice(pushToken: token, platform: _push.platform);
    await _prefs.setString(deviceIdKey, created.id);
    if (current != null) state = const AsyncData([]);
    return created.id;
  }

  Future<void> _onTokenRefresh(String token) async {
    final id = deviceId;
    if (id == null) return;
    try {
      await _api.updateDevice(id, pushToken: token, platform: _push.platform);
    } catch (e) {
      // Retried on the next refresh or the next save; the old token keeps working for a while.
      debugPrint('Push token refresh not sent to the backend: $e');
    }
  }
}

/// Recent events matching any of the device's subscriptions, once each, newest first.
@riverpod
Future<List<DossierEvent>> recentEvents(Ref ref) async {
  final subscriptions = await ref.watch(mySubscriptionsProvider.future);
  final deviceId = ref.read(mySubscriptionsProvider.notifier).deviceId;
  if (subscriptions.isEmpty || deviceId == null) return const [];
  return ref.watch(perimetreApiProvider).deviceEvents(deviceId);
}

/// Finds the dossier of a tapped push: /dossiers around [subscription] first, then the device's event feed,
/// since /dossiers is capped at 200 results and hides TERMINE dossiers. Null when not found.
Future<Dossier?> findPushedDossier(
  PerimetreApi api, {
  required String deviceId,
  required Subscription? subscription,
  required String idDossier,
}) async {
  if (subscription != null) {
    final around = await api.dossiers(lat: subscription.lat, lng: subscription.lng, radius: subscription.radiusM);
    for (final d in around) {
      if (d.idDossier == idDossier) return d;
    }
  }
  final events = await api.deviceEvents(deviceId, limit: 200);
  for (final e in events) {
    if (e.dossier.idDossier == idDossier) return e.dossier;
  }
  return null;
}
