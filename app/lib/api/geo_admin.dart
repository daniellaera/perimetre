import 'package:dio/dio.dart';

import 'models.dart';

/// Address search on the federal geo.admin.ch SearchServer.
class GeoAdminClient {
  GeoAdminClient(this._dio);

  final Dio _dio;

  static const _url = 'https://api3.geo.admin.ch/rest/services/api/SearchServer';

  /// The SearchServer has no canton filter (and its `bbox` parameter is ignored for addresses),
  /// so fetch a wider page and keep only canton GE results.
  static const _fetchLimit = 50;
  static const _maxSuggestions = 5;

  Future<List<AddressSuggestion>> search(String query) async {
    final response = await _dio.get<Map<String, dynamic>>(
      _url,
      queryParameters: {
        'searchText': query,
        'type': 'locations',
        'origins': 'address',
        'limit': _fetchLimit,
        'sr': 4326,
      },
    );
    final results = (response.data?['results'] as List? ?? const []).cast<Map<String, dynamic>>();
    return results
        .map((r) => r['attrs'] as Map<String, dynamic>)
        .where((attrs) => (attrs['detail'] as String? ?? '').endsWith(' ge'))
        .take(_maxSuggestions)
        .map(AddressSuggestion.fromJson)
        .toList();
  }
}
