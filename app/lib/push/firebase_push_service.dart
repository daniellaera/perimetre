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
  Future<PushPermission> permission() async => _map((await _messaging.getNotificationSettings()).authorizationStatus);

  @override
  Future<PushPermission> requestPermission() async =>
      _map((await _messaging.requestPermission()).authorizationStatus);

  static PushPermission _map(AuthorizationStatus status) => switch (status) {
    AuthorizationStatus.authorized || AuthorizationStatus.provisional => PushPermission.granted,
    AuthorizationStatus.denied || AuthorizationStatus.deniedPermanently => PushPermission.denied,
    AuthorizationStatus.notDetermined => PushPermission.notDetermined,
  };

  @override
  Future<String?> currentToken() async {
    // Firebase iOS SDK 13 hands out a token before APNs has delivered the device token (it only logs "APNS
    // device token not set before retrieving FCM Token"); that token is never delivered. Once the APNs token is
    // set, the SDK drops it and fetches a mapped one (announced through onTokenRefresh), so getToken() is safe.
    if (defaultTargetPlatform == TargetPlatform.iOS && await _messaging.getAPNSToken() == null) return null;
    // ignore: deprecated_member_use
    return _messaging.getToken();
  }

  @override
  // ignore: deprecated_member_use
  Stream<String> get onTokenRefresh => _messaging.onTokenRefresh;

  @override
  Stream<Map<String, dynamic>> get onOpened => FirebaseMessaging.onMessageOpenedApp.map((m) => m.data);

  @override
  Future<Map<String, dynamic>?> initialOpened() async => (await _messaging.getInitialMessage())?.data;
}
