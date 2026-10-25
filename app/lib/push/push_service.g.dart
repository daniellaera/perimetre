// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'push_service.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning
/// Overridden in main() with the result of initPush().

@ProviderFor(pushService)
final pushServiceProvider = PushServiceProvider._();

/// Overridden in main() with the result of initPush().

final class PushServiceProvider
    extends $FunctionalProvider<PushService, PushService, PushService>
    with $Provider<PushService> {
  /// Overridden in main() with the result of initPush().
  PushServiceProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'pushServiceProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$pushServiceHash();

  @$internal
  @override
  $ProviderElement<PushService> $createElement($ProviderPointer pointer) =>
      $ProviderElement(pointer);

  @override
  PushService create(Ref ref) {
    return pushService(ref);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(PushService value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<PushService>(value),
    );
  }
}

String _$pushServiceHash() => r'abc3f87b62be79f3a2138807169569fdfe567718';
