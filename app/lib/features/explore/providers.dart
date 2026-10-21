import 'package:dio/dio.dart';
import 'package:riverpod_annotation/riverpod_annotation.dart';

import '../../api/geo_admin.dart';
import '../../api/models.dart';
import '../../api/perimetre_api.dart';

part 'providers.g.dart';

@Riverpod(keepAlive: true)
GeoAdminClient geoAdmin(Ref ref) => GeoAdminClient(
  Dio(BaseOptions(connectTimeout: const Duration(seconds: 10), receiveTimeout: const Duration(seconds: 10))),
);

@Riverpod(keepAlive: true)
PerimetreApi perimetreApi(Ref ref) => PerimetreApi(
  Dio(
    BaseOptions(
      baseUrl: apiBaseUrl,
      connectTimeout: const Duration(seconds: 10),
      receiveTimeout: const Duration(seconds: 20),
    ),
  ),
);

const minRadius = 100;
const maxRadius = 1000;
const defaultRadius = 200;

@Riverpod(keepAlive: true)
class SelectedAddress extends _$SelectedAddress {
  @override
  AddressSuggestion? build() => null;

  void select(AddressSuggestion address) => state = address;
}

@Riverpod(keepAlive: true)
class RadiusMetres extends _$RadiusMetres {
  @override
  int build() => defaultRadius;

  void set(int radius) => state = radius;
}

/// Dossiers around the selected address; refetches when the address or radius changes.
@riverpod
Future<List<Dossier>> dossiers(Ref ref) async {
  final address = ref.watch(selectedAddressProvider);
  final radius = ref.watch(radiusMetresProvider);
  if (address == null) return const [];

  final cancelToken = CancelToken();
  ref.onDispose(cancelToken.cancel);
  return ref
      .watch(perimetreApiProvider)
      .dossiers(lat: address.lat, lng: address.lon, radius: radius, cancelToken: cancelToken);
}
