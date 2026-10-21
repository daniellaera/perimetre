import 'package:dio/dio.dart';

import 'models.dart';

/// Backend base URL, set with `--dart-define=API_BASE_URL=...`.
const apiBaseUrl = String.fromEnvironment('API_BASE_URL', defaultValue: 'http://localhost:8080');

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
}
