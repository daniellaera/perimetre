import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'push_service.g.dart';

/// The OS permission to show notifications. Provisional counts as granted.
enum PushPermission { granted, denied, notDetermined }

/// Push notifications, backed by Firebase Messaging or, when Firebase is not configured, by a stub.
abstract interface class PushService {
  /// False for the stub: tokens are fake and no push will ever arrive.
  bool get isConfigured;

  /// "ios" or "android", as stored in the subscription.
  String get platform;

  /// The current permission, without prompting.
  Future<PushPermission> permission();

  /// Shows the OS prompt when the permission is not determined yet; returns the resulting permission.
  Future<PushPermission> requestPermission();

  /// The token the backend may send pushes to, or null while there is none yet. On iOS that is until APNs has
  /// delivered the device token: an FCM token fetched before it is not mapped to APNs, and FCM accepts pushes to
  /// it without ever delivering them. Does not wait; callers retry.
  Future<String?> currentToken();

  /// Fires when the FCM token changes (also with tokens fetched before APNs was ready): a hint to call
  /// [currentToken] again, not a token to send as is.
  Stream<String> get onTokenRefresh;

  /// `data` of pushes tapped while the app was in the background:
  /// `{id_dossier, kind, new_groupe, subscription_id}`, `{kind: SUMMARY, count}` or `{kind: TEST}`.
  Stream<Map<String, dynamic>> get onOpened;

  /// `data` of the push that launched the app, if any.
  Future<Map<String, dynamic>?> initialOpened();
}

/// Overridden in main() with the result of initPush().
@Riverpod(keepAlive: true)
PushService pushService(Ref ref) => throw UnimplementedError('pushServiceProvider must be overridden');
