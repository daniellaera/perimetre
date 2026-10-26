import 'package:riverpod_annotation/riverpod_annotation.dart';

part 'push_service.g.dart';

/// Push notifications, backed by Firebase Messaging or, when Firebase is not configured, by a stub.
abstract interface class PushService {
  /// False for the stub: tokens are fake and no push will ever arrive.
  bool get isConfigured;

  /// "ios" or "android", as stored in the subscription.
  String get platform;

  /// Asks the OS for permission to show notifications; true when granted.
  Future<bool> requestPermission();

  /// The token the backend sends pushes to.
  Future<String> getToken();

  Stream<String> get onTokenRefresh;

  /// `data` of pushes tapped while the app was in the background:
  /// `{id_dossier, kind, new_groupe, subscription_id}`, or `{kind: SUMMARY, count}`.
  Stream<Map<String, dynamic>> get onOpened;

  /// `data` of the push that launched the app, if any.
  Future<Map<String, dynamic>?> initialOpened();
}

/// Overridden in main() with the result of initPush().
@Riverpod(keepAlive: true)
PushService pushService(Ref ref) => throw UnimplementedError('pushServiceProvider must be overridden');
