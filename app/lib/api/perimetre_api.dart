import 'package:dio/dio.dart';

import 'models.dart';

/// Backend base URL, set with `--dart-define=API_BASE_URL=...`.
const apiBaseUrl = String.fromEnvironment('API_BASE_URL', defaultValue: 'http://localhost:8090');

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

  Future<Subscription> createSubscription(SubscriptionInput input) async {
    final response = await _dio.post<Map<String, dynamic>>('/subscriptions', data: input.toJson());
    return Subscription.fromJson(response.data!);
  }

  /// Null when the subscription no longer exists (e.g. deleted after repeated invalid push tokens).
  Future<Subscription?> subscription(String id) =>
      _orNullOn404(() => _dio.get<Map<String, dynamic>>('/subscriptions/$id'));

  /// Null when the subscription no longer exists.
  Future<Subscription?> updateSubscription(String id, SubscriptionInput input) =>
      _orNullOn404(() => _dio.put<Map<String, dynamic>>('/subscriptions/$id', data: input.toJson()));

  /// Succeeds when the subscription is already gone.
  Future<void> deleteSubscription(String id) async {
    try {
      await _dio.delete<void>('/subscriptions/$id');
    } on DioException catch (e) {
      if (e.response?.statusCode != 404) rethrow;
    }
  }

  /// Events in the subscription's perimeter, newest first.
  Future<List<DossierEvent>> subscriptionEvents(String id, {int limit = 50}) async {
    final response = await _dio.get<List<dynamic>>('/subscriptions/$id/events', queryParameters: {'limit': limit});
    return (response.data ?? const [])
        .map((json) => DossierEvent.fromJson(json as Map<String, dynamic>))
        .toList();
  }

  Future<Subscription?> _orNullOn404(Future<Response<Map<String, dynamic>>> Function() request) async {
    try {
      return Subscription.fromJson((await request()).data!);
    } on DioException catch (e) {
      if (e.response?.statusCode == 404) return null;
      rethrow;
    }
  }
}
