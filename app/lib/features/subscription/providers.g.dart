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

/// The device's subscription. Its id (the only handle on it: there is no account) lives in shared_preferences.

@ProviderFor(MySubscription)
final mySubscriptionProvider = MySubscriptionProvider._();

/// The device's subscription. Its id (the only handle on it: there is no account) lives in shared_preferences.
final class MySubscriptionProvider
    extends $AsyncNotifierProvider<MySubscription, Subscription?> {
  /// The device's subscription. Its id (the only handle on it: there is no account) lives in shared_preferences.
  MySubscriptionProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'mySubscriptionProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$mySubscriptionHash();

  @$internal
  @override
  MySubscription create() => MySubscription();
}

String _$mySubscriptionHash() => r'53e6b970f3d514d069adb314944ccdc2467b90bc';

/// The device's subscription. Its id (the only handle on it: there is no account) lives in shared_preferences.

abstract class _$MySubscription extends $AsyncNotifier<Subscription?> {
  FutureOr<Subscription?> build();
  @$mustCallSuper
  @override
  WhenComplete runBuild() {
    final ref = this.ref as $Ref<AsyncValue<Subscription?>, Subscription?>;
    final element =
        ref.element
            as $ClassProviderElement<
              AnyNotifier<AsyncValue<Subscription?>, Subscription?>,
              AsyncValue<Subscription?>,
              Object?,
              Object?
            >;
    return element.handleCreate(ref, build);
  }
}

/// Recent events in the subscription's perimeter, newest first.

@ProviderFor(recentEvents)
final recentEventsProvider = RecentEventsProvider._();

/// Recent events in the subscription's perimeter, newest first.

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
  /// Recent events in the subscription's perimeter, newest first.
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

String _$recentEventsHash() => r'8aebc0b75810cd981c5ee92f8ca34133f91d530e';
