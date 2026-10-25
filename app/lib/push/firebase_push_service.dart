import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';

import 'push_service.dart';

/// Uses FCM registration tokens (getToken/onTokenRefresh), deprecated since firebase_messaging 17 in favour of
/// register()/onRegistered, which yield a Firebase Installation ID. The backend sends with Message.setToken
/// (firebase-admin deprecates it for setFid): migrate both sides together.
class FirebasePushService implements PushService {
  FirebasePushService._(this._messaging);

  final FirebaseMessaging _messaging;

  static Future<FirebasePushService> create() async {
    final messaging = FirebaseMessaging.instance;
    // iOS hides notifications while the app is in the foreground unless asked otherwise.
    await messaging.setForegroundNotificationPresentationOptions(alert: true, badge: true, sound: true);
    return FirebasePushService._(messaging);
  }

  @override
  bool get isConfigured => true;

  @override
  String get platform => defaultTargetPlatform == TargetPlatform.iOS ? 'ios' : 'android';

  @override
  Future<bool> requestPermission() async {
    final settings = await _messaging.requestPermission();
    return settings.authorizationStatus == AuthorizationStatus.authorized ||
        settings.authorizationStatus == AuthorizationStatus.provisional;
  }

  @override
  Future<String> getToken() async {
    // On iOS, getToken() fails until APNs has delivered the device token, which can take a moment.
    if (defaultTargetPlatform == TargetPlatform.iOS) {
      for (var i = 0; i < 20 && await _messaging.getAPNSToken() == null; i++) {
        await Future<void>.delayed(const Duration(milliseconds: 500));
      }
    }
    // ignore: deprecated_member_use
    final token = await _messaging.getToken();
    if (token == null) throw StateError('Firebase returned no push token');
    return token;
  }

  @override
  // ignore: deprecated_member_use
  Stream<String> get onTokenRefresh => _messaging.onTokenRefresh;

  @override
  Stream<Map<String, dynamic>> get onOpened => FirebaseMessaging.onMessageOpenedApp.map((m) => m.data);

  @override
  Future<Map<String, dynamic>?> initialOpened() async => (await _messaging.getInitialMessage())?.data;
}
