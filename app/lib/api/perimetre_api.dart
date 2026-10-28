import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'models.dart';

/// Backend base URL: `--dart-define=API_BASE_URL=...` when given, otherwise production in release
/// builds and the local backend in debug and profile builds.
const apiBaseUrl = String.fromEnvironment(
  'API_BASE_URL',
  defaultValue: kReleaseMode ? 'https://api.perimetre.ch' : 'http://localhost:8090',
);

/// The device already has the backend's maximum number of subscriptions (409 SUBSCRIPTION_LIMIT_REACHED).
class SubscriptionLimitException implements Exception {
  const SubscriptionLimitException(this.message);

  /// French, ready to show.
  final String message;

  @override
  String toString() => message;
}

/// A test push was sent less than a minute ago (429 TEST_PUSH_RATE_LIMITED).
class TestPushRateLimitedException implements Exception {
  const TestPushRateLimitedException(this.message);

  /// French, ready to show.
  final String message;

  @override
  String toString() => message;
}

/// The backend's answer to a test push: FCM accepted it, refused the token, or failed otherwise.
enum TestPushStatus { sent, invalidToken, failed }

class PerimetreApi {
  PerimetreApi(this._dio);

  final Dio _dio;

  Future<List<Dossier>> dossiers({
    required double lat,
    required double lng,
    required int radius,
    CancelToken? cancelToken,
  }) async {
    final response = await _dio.get<List<dynamic>>(
      '/dossiers',
      queryParameters: {'lat': lat, 'lng': lng, 'radius': radius},
      cancelToken: cancelToken,
    );
    return (response.data ?? const [])
        .map((json) => Dossier.fromJson(json as Map<String, dynamic>))
        .toList();
  }

  Future<Device> createDevice({required String pushToken, required String platform}) async {
    final response = await _dio.post<Map<String, dynamic>>(
      '/devices',
      data: {'push_token': pushToken, 'platform': platform},
    );
    return Device.fromJson(response.data!);
  }

  /// New push token. Null when the device no longer exists (deleted after repeated invalid tokens).
  Future<Device?> updateDevice(String id, {required String pushToken, required String platform}) => _orNullOn404(
    () => _dio.put<Map<String, dynamic>>('/devices/$id', data: {'push_token': pushToken, 'platform': platform}),
    Device.fromJson,
  );

  /// Null when the device no longer exists.
  Future<List<Subscription>?> subscriptions(String deviceId) async {
    try {
      final response = await _dio.get<List<dynamic>>('/devices/$deviceId/subscriptions');
      return (response.data ?? const [])
          .map((json) => Subscription.fromJson(json as Map<String, dynamic>))
          .toList();
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) return null;
      rethrow;
    }
  }

  /// Throws [SubscriptionLimitException] at the per-device cap.
  Future<Subscription> createSubscription(String deviceId, SubscriptionInput input) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>('/devices/$deviceId/subscriptions', data: input.toJson());
      return Subscription.fromJson(response.data!);
    } on DioException catch (e) {
      final body = e.response?.data;
      if (e.response?.statusCode == 409 && body is Map && body['code'] == 'SUBSCRIPTION_LIMIT_REACHED') {
        throw SubscriptionLimitException(body['detail'] as String? ?? 'Nombre maximum de périmètres atteint.');
      }
      rethrow;
    }
  }

  /// Null when the subscription no longer exists.
  Future<Subscription?> updateSubscription(String deviceId, String id, SubscriptionInput input) => _orNullOn404(
    () => _dio.put<Map<String, dynamic>>('/devices/$deviceId/subscriptions/$id', data: input.toJson()),
    Subscription.fromJson,
  );

  /// Succeeds when the subscription is already gone.
  Future<void> deleteSubscription(String deviceId, String id) async {
    try {
      await _dio.delete<void>('/devices/$deviceId/subscriptions/$id');
    } on DioException catch (e) {
      if (e.response?.statusCode != 404) rethrow;
    }
  }

  /// Events matching any of the device's subscriptions, once each, newest first.
  Future<List<DossierEvent>> deviceEvents(String deviceId, {int limit = 50}) async {
    final response = await _dio.get<List<dynamic>>('/devices/$deviceId/events', queryParameters: {'limit': limit});
    return (response.data ?? const [])
        .map((json) => DossierEvent.fromJson(json as Map<String, dynamic>))
        .toList();
  }

  /// One generic push to the device. Null when the device no longer exists; throws
  /// [TestPushRateLimitedException] within a minute of the previous one.
  Future<TestPushStatus?> testPush(String deviceId) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>('/devices/$deviceId/test-push');
      return switch (response.data?['status']) {
        'SENT' => TestPushStatus.sent,
        'INVALID_TOKEN' => TestPushStatus.invalidToken,
        _ => TestPushStatus.failed,
      };
    } on DioException catch (e) {
      final body = e.response?.data;
      if (e.response?.statusCode == 404) return null;
      if (e.response?.statusCode == 429 && body is Map && body['code'] == 'TEST_PUSH_RATE_LIMITED') {
        throw TestPushRateLimitedException(body['detail'] as String? ?? 'Réessayez dans une minute.');
      }
      rethrow;
    }
  }

  Future<T?> _orNullOn404<T>(
    Future<Response<Map<String, dynamic>>> Function() request,
    T Function(Map<String, dynamic>) fromJson,
  ) async {
    try {
      return fromJson((await request()).data!);
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) return null;
      rethrow;
    }
  }
}
