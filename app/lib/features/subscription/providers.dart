import 'dart:async';

import 'package:riverpod_annotation/riverpod_annotation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../../api/models.dart';
import '../../api/perimetre_api.dart';
import '../../push/push_service.dart';
import '../../push/token_sync.dart';
import '../explore/providers.dart';

part 'providers.g.dart';

/// Overridden in main() with the instance loaded before runApp.
@Riverpod(keepAlive: true)
SharedPreferences sharedPreferences(Ref ref) => throw UnimplementedError('sharedPreferencesProvider must be overridden');

/// Keys in shared_preferences. The device id is the only handle on the device's subscriptions (no account).
const deviceIdKey = 'device_id';

/// The single-subscription app build stored its subscription id here. The backend gave that subscription a
/// device with the same id, so it is adopted as the device id.
const legacySubscriptionIdKey = 'subscription_id';

/// Started by the home shell: keeps the backend's push token of this device current (see [TokenSync]).
@Riverpod(keepAlive: true)
TokenSync tokenSync(Ref ref) {
  final prefs = ref.watch(sharedPreferencesProvider);
  final sync = TokenSync(
    prefs: prefs,
    api: ref.watch(perimetreApiProvider),
    push: ref.watch(pushServiceProvider),
    deviceId: () => prefs.getString(deviceIdKey),
  );
  ref.onDispose(sync.dispose);
  return sync;
}

/// The OS permission to show notifications; invalidated by the home shell on every resume (it can be changed in
/// the Settings app at any time).
@Riverpod(keepAlive: true)
Future<PushPermission> notificationPermission(Ref ref) => ref.watch(pushServiceProvider).permission();

/// The device's subscriptions (perimeters), oldest first. Empty until the first one is added.
@Riverpod(keepAlive: true)
class MySubscriptions extends _$MySubscriptions {
  SharedPreferences get _prefs => ref.read(sharedPreferencesProvider);
  PerimetreApi get _api => ref.read(perimetreApiProvider);
  PushService get _push => ref.read(pushServiceProvider);
  TokenSync get _tokenSync => ref.read(tokenSyncProvider);

  /// Null until the first subscription is added (or after the backend forgot the device).
  String? get deviceId => _prefs.getString(deviceIdKey);

  @override
  Future<List<Subscription>> build() async {
    await _adoptLegacySubscription();
    final id = deviceId;
    if (id == null) return const [];
    final subscriptions = await _api.subscriptions(id);
    // Deleted server-side (e.g. after repeated invalid tokens): forget it. Network errors keep the id.
    if (subscriptions == null) {
      await _prefs.remove(deviceIdKey);
      await _tokenSync.forget();
    }
    return subscriptions ?? const [];
  }

  Future<void> _adoptLegacySubscription() async {
    final legacyId = _prefs.getString(legacySubscriptionIdKey);
    if (legacyId == null) return;
    if (deviceId == null) await _prefs.setString(deviceIdKey, legacyId);
    await _prefs.remove(legacySubscriptionIdKey);
  }

  /// Registers the device (or refreshes its push token), then creates the subscription. Throws
  /// [SubscriptionLimitException] at the backend's cap, [PushNotReadyException] without a usable push token. The
  /// notification permission is asked by the screen: the token does not depend on it.
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
    final token = await _tokenSync.tokenForRegistration();
    final current = deviceId;
    final String id;
    if (current != null && await _api.updateDevice(current, pushToken: token, platform: _push.platform) != null) {
      id = current;
    } else {
      id = (await _api.createDevice(pushToken: token, platform: _push.platform)).id;
      await _prefs.setString(deviceIdKey, id);
      if (current != null) state = const AsyncData([]);
    }
    await _tokenSync.recordSent(token);
    // A refresh during the registration had no device to update: catch up.
    unawaited(_tokenSync.sync());
    return id;
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
