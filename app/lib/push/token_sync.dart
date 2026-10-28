import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../api/perimetre_api.dart';
import 'push_service.dart';

/// No usable push token yet (iOS: APNs has not delivered the device token), so the device cannot be registered.
class PushNotReadyException implements Exception {
  const PushNotReadyException();
}

/// Keeps the push token the backend holds for this device equal to the app's current one. [sync] runs at app
/// start, on every resume and on every FCM token refresh, and PUTs only when the token differs from the last one
/// sent (kept in shared_preferences). A token is only taken from [PushService.currentToken], which withholds the
/// undeliverable ones fetched before APNs is ready; while there is none, or the backend is unreachable, it
/// retries with [retryDelays], staying at the last delay.
class TokenSync {
  TokenSync({
    required this._prefs,
    required this._api,
    required this._push,
    required this._deviceId,
    this.retryDelays = const [Duration(seconds: 2), Duration(seconds: 5), Duration(seconds: 15), Duration(seconds: 60)],
  });

  /// The last token the backend acknowledged for this device.
  static const sentTokenKey = 'push_token_sent';

  final SharedPreferences _prefs;
  final PerimetreApi _api;
  final PushService _push;
  final String? Function() _deviceId;
  final List<Duration> retryDelays;

  StreamSubscription<String>? _refreshes;
  Timer? _retry;
  int _retries = 0;
  Future<void>? _running;
  bool _again = false;

  /// Listens to token refreshes and syncs once. Idempotent.
  void start() {
    _refreshes ??= _push.onTokenRefresh.listen((_) => sync());
    unawaited(sync());
  }

  void dispose() {
    _refreshes?.cancel();
    _retry?.cancel();
  }

  /// One pass at a time: a call during a pass gets that pass's future and makes it run once more afterwards.
  Future<void> sync() {
    final running = _running;
    if (running != null) {
      _again = true;
      return running;
    }
    return _running = _passes().whenComplete(() => _running = null);
  }

  Future<void> _passes() async {
    do {
      _again = false;
      await _pass();
    } while (_again);
  }

  Future<void> _pass() async {
    final deviceId = _deviceId();
    if (deviceId == null) return; // The first save registers the device with a fresh token.
    try {
      final token = await _push.currentToken();
      if (token == null) return _scheduleRetry();
      if (token != _prefs.getString(sentTokenKey)) {
        // Null: the backend deleted the device; the subscriptions list forgets its id on the next load.
        if (await _api.updateDevice(deviceId, pushToken: token, platform: _push.platform) != null) {
          await _prefs.setString(sentTokenKey, token);
        }
      }
      _retries = 0;
      _retry?.cancel();
      _retry = null;
    } catch (e) {
      debugPrint('Push token not synced with the backend: $e');
      _scheduleRetry();
    }
  }

  void _scheduleRetry() {
    _retry?.cancel();
    _retry = Timer(retryDelays[_retries < retryDelays.length ? _retries++ : retryDelays.length - 1], sync);
  }

  /// A token for registering the device, waiting up to [timeout] for one; else [PushNotReadyException].
  Future<String> tokenForRegistration({Duration timeout = const Duration(seconds: 30)}) async {
    final deadline = DateTime.now().add(timeout);
    while (true) {
      final token = await _push.currentToken();
      if (token != null) return token;
      if (DateTime.now().isAfter(deadline)) throw const PushNotReadyException();
      await Future<void>.delayed(const Duration(milliseconds: 500));
    }
  }

  /// [token] was just sent with a device POST or PUT.
  Future<void> recordSent(String token) => _prefs.setString(sentTokenKey, token);

  /// The backend no longer has the device.
  Future<void> forget() => _prefs.remove(sentTokenKey);
}
