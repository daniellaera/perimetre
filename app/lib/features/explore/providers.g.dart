// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'providers.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning

@ProviderFor(geoAdmin)
final geoAdminProvider = GeoAdminProvider._();

final class GeoAdminProvider
    extends $FunctionalProvider<GeoAdminClient, GeoAdminClient, GeoAdminClient>
    with $Provider<GeoAdminClient> {
  GeoAdminProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'geoAdminProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$geoAdminHash();

  @$internal
  @override
  $ProviderElement<GeoAdminClient> $createElement($ProviderPointer pointer) =>
      $ProviderElement(pointer);

  @override
  GeoAdminClient create(Ref ref) {
    return geoAdmin(ref);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(GeoAdminClient value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<GeoAdminClient>(value),
    );
  }
}

String _$geoAdminHash() => r'c276cf7df03c73703fa8b03f68d15d5a60833060';

@ProviderFor(perimetreApi)
final perimetreApiProvider = PerimetreApiProvider._();

final class PerimetreApiProvider
    extends $FunctionalProvider<PerimetreApi, PerimetreApi, PerimetreApi>
    with $Provider<PerimetreApi> {
  PerimetreApiProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'perimetreApiProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$perimetreApiHash();

  @$internal
  @override
  $ProviderElement<PerimetreApi> $createElement($ProviderPointer pointer) =>
      $ProviderElement(pointer);

  @override
  PerimetreApi create(Ref ref) {
    return perimetreApi(ref);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(PerimetreApi value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<PerimetreApi>(value),
    );
  }
}

String _$perimetreApiHash() => r'61ddeab3e0daceea10975d33aa0626ac79d59baa';

@ProviderFor(SelectedAddress)
final selectedAddressProvider = SelectedAddressProvider._();

final class SelectedAddressProvider
    extends $NotifierProvider<SelectedAddress, AddressSuggestion?> {
  SelectedAddressProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'selectedAddressProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$selectedAddressHash();

  @$internal
  @override
  SelectedAddress create() => SelectedAddress();

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(AddressSuggestion? value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<AddressSuggestion?>(value),
    );
  }
}

String _$selectedAddressHash() => r'df8059d8ec5064b3bdfbe48192b1f99b5164f1a2';

abstract class _$SelectedAddress extends $Notifier<AddressSuggestion?> {
  AddressSuggestion? build();
  @$mustCallSuper
  @override
  WhenComplete runBuild() {
    final ref = this.ref as $Ref<AddressSuggestion?, AddressSuggestion?>;
    final element =
        ref.element
            as $ClassProviderElement<
              AnyNotifier<AddressSuggestion?, AddressSuggestion?>,
              AddressSuggestion?,
              Object?,
              Object?
            >;
    return element.handleCreate(ref, build);
  }
}

@ProviderFor(RadiusMetres)
final radiusMetresProvider = RadiusMetresProvider._();

final class RadiusMetresProvider extends $NotifierProvider<RadiusMetres, int> {
  RadiusMetresProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'radiusMetresProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$radiusMetresHash();

  @$internal
  @override
  RadiusMetres create() => RadiusMetres();

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(int value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<int>(value),
    );
  }
}

String _$radiusMetresHash() => r'c528229c618e766c9d1348e0cbc6115d8954f865';

abstract class _$RadiusMetres extends $Notifier<int> {
  int build();
  @$mustCallSuper
  @override
  WhenComplete runBuild() {
    final ref = this.ref as $Ref<int, int>;
    final element =
        ref.element
            as $ClassProviderElement<
              AnyNotifier<int, int>,
              int,
              Object?,
              Object?
            >;
    return element.handleCreate(ref, build);
  }
}

/// Dossiers around the selected address; refetches when the address or radius changes.

@ProviderFor(dossiers)
final dossiersProvider = DossiersProvider._();

/// Dossiers around the selected address; refetches when the address or radius changes.

final class DossiersProvider
    extends
        $FunctionalProvider<
          AsyncValue<List<Dossier>>,
          List<Dossier>,
          FutureOr<List<Dossier>>
        >
    with $FutureModifier<List<Dossier>>, $FutureProvider<List<Dossier>> {
  /// Dossiers around the selected address; refetches when the address or radius changes.
  DossiersProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'dossiersProvider',
        isAutoDispose: true,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$dossiersHash();

  @$internal
  @override
  $FutureProviderElement<List<Dossier>> $createElement(
    $ProviderPointer pointer,
  ) => $FutureProviderElement(pointer);

  @override
  FutureOr<List<Dossier>> create(Ref ref) {
    return dossiers(ref);
  }
}

String _$dossiersHash() => r'1ce03560c8d5e9fb2e02e469dd2c744790fafcd1';
