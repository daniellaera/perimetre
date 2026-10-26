// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'providers.dart';

// **************************************************************************
// RiverpodGenerator
// **************************************************************************

// GENERATED CODE - DO NOT MODIFY BY HAND
// ignore_for_file: type=lint, type=warning
/// Overridden in main() with the instance loaded before runApp.

@ProviderFor(sharedPreferences)
final sharedPreferencesProvider = SharedPreferencesProvider._();

/// Overridden in main() with the instance loaded before runApp.

final class SharedPreferencesProvider
    extends
        $FunctionalProvider<
          SharedPreferences,
          SharedPreferences,
          SharedPreferences
        >
    with $Provider<SharedPreferences> {
  /// Overridden in main() with the instance loaded before runApp.
  SharedPreferencesProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'sharedPreferencesProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$sharedPreferencesHash();

  @$internal
  @override
  $ProviderElement<SharedPreferences> $createElement(
    $ProviderPointer pointer,
  ) => $ProviderElement(pointer);

  @override
  SharedPreferences create(Ref ref) {
    return sharedPreferences(ref);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(SharedPreferences value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<SharedPreferences>(value),
    );
  }
}

String _$sharedPreferencesHash() => r'70ef90bd70df9f89260fca9b542d9f8d25d8e3cb';

/// The device's subscriptions (perimeters), oldest first. Empty until the first one is added.

@ProviderFor(MySubscriptions)
final mySubscriptionsProvider = MySubscriptionsProvider._();

/// The device's subscriptions (perimeters), oldest first. Empty until the first one is added.
final class MySubscriptionsProvider
    extends $AsyncNotifierProvider<MySubscriptions, List<Subscription>> {
  /// The device's subscriptions (perimeters), oldest first. Empty until the first one is added.
  MySubscriptionsProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'mySubscriptionsProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$mySubscriptionsHash();

  @$internal
  @override
  MySubscriptions create() => MySubscriptions();
}

String _$mySubscriptionsHash() => r'1dbb2a8401e9479a102e310f01a00d95434d5ff2';

/// The device's subscriptions (perimeters), oldest first. Empty until the first one is added.

abstract class _$MySubscriptions extends $AsyncNotifier<List<Subscription>> {
  FutureOr<List<Subscription>> build();
  @$mustCallSuper
  @override
  WhenComplete runBuild() {
    final ref =
        this.ref as $Ref<AsyncValue<List<Subscription>>, List<Subscription>>;
    final element =
        ref.element
            as $ClassProviderElement<
              AnyNotifier<AsyncValue<List<Subscription>>, List<Subscription>>,
              AsyncValue<List<Subscription>>,
              Object?,
              Object?
            >;
    return element.handleCreate(ref, build);
  }
}

/// Recent events matching any of the device's subscriptions, once each, newest first.

@ProviderFor(recentEvents)
final recentEventsProvider = RecentEventsProvider._();

/// Recent events matching any of the device's subscriptions, once each, newest first.

final class RecentEventsProvider
    extends
        $FunctionalProvider<
          AsyncValue<List<DossierEvent>>,
          List<DossierEvent>,
          FutureOr<List<DossierEvent>>
        >
    with
        $FutureModifier<List<DossierEvent>>,
        $FutureProvider<List<DossierEvent>> {
  /// Recent events matching any of the device's subscriptions, once each, newest first.
  RecentEventsProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'recentEventsProvider',
        isAutoDispose: true,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$recentEventsHash();

  @$internal
  @override
  $FutureProviderElement<List<DossierEvent>> $createElement(
    $ProviderPointer pointer,
  ) => $FutureProviderElement(pointer);

  @override
  FutureOr<List<DossierEvent>> create(Ref ref) {
    return recentEvents(ref);
  }
}

String _$recentEventsHash() => r'c850bb69fe9173531294ec1bc5c1457952ee077c';
