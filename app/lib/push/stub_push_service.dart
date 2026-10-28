import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'push_service.dart';

/// Used while Firebase is not configured: permission is always granted and the token is a stable fake
/// `dev-token-<uuid>`, so subscriptions can be created end to end against the local backend
/// (which logs pushes instead of sending them).
class StubPushService implements PushService {
  StubPushService(this._prefs) {
    debugPrint('Firebase not configured: using StubPushService with a fake push token');
  }

  static const _tokenKey = 'dev_push_token';

  final SharedPreferences _prefs;

  @override
  bool get isConfigured => false;

  @override
  String get platform => defaultTargetPlatform == TargetPlatform.iOS ? 'ios' : 'android';

  @override
  Future<PushPermission> permission() async => PushPermission.granted;

  @override
  Future<PushPermission> requestPermission() async => PushPermission.granted;

  @override
  Future<String?> currentToken() async {
    final existing = _prefs.getString(_tokenKey);
    if (existing != null) return existing;
    final token = 'dev-token-${_uuidV4()}';
    await _prefs.setString(_tokenKey, token);
    return token;
  }

  @override
  Stream<String> get onTokenRefresh => const Stream.empty();

  @override
  Stream<Map<String, dynamic>> get onOpened => const Stream.empty();

  @override
  Future<Map<String, dynamic>?> initialOpened() async => null;

  static String _uuidV4() {
    final random = Random.secure();
    final bytes = List<int>.generate(16, (_) => random.nextInt(256));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    final hex = bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
    return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-'
        '${hex.substring(16, 20)}-${hex.substring(20)}';
  }
}
