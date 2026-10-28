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

/// Started by the home shell: keeps the backend's push token of this device current (see [TokenSync]).

@ProviderFor(tokenSync)
final tokenSyncProvider = TokenSyncProvider._();

/// Started by the home shell: keeps the backend's push token of this device current (see [TokenSync]).

final class TokenSyncProvider
    extends $FunctionalProvider<TokenSync, TokenSync, TokenSync>
    with $Provider<TokenSync> {
  /// Started by the home shell: keeps the backend's push token of this device current (see [TokenSync]).
  TokenSyncProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'tokenSyncProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$tokenSyncHash();

  @$internal
  @override
  $ProviderElement<TokenSync> $createElement($ProviderPointer pointer) =>
      $ProviderElement(pointer);

  @override
  TokenSync create(Ref ref) {
    return tokenSync(ref);
  }

  /// {@macro riverpod.override_with_value}
  Override overrideWithValue(TokenSync value) {
    return $ProviderOverride(
      origin: this,
      providerOverride: $SyncValueProvider<TokenSync>(value),
    );
  }
}

String _$tokenSyncHash() => r'b085cb619c687bc606fb647fc7c3f12e137af9d1';

/// The OS permission to show notifications; invalidated by the home shell on every resume (it can be changed in
/// the Settings app at any time).

@ProviderFor(notificationPermission)
final notificationPermissionProvider = NotificationPermissionProvider._();

/// The OS permission to show notifications; invalidated by the home shell on every resume (it can be changed in
/// the Settings app at any time).

final class NotificationPermissionProvider
    extends
        $FunctionalProvider<
          AsyncValue<PushPermission>,
          PushPermission,
          FutureOr<PushPermission>
        >
    with $FutureModifier<PushPermission>, $FutureProvider<PushPermission> {
  /// The OS permission to show notifications; invalidated by the home shell on every resume (it can be changed in
  /// the Settings app at any time).
  NotificationPermissionProvider._()
    : super(
        from: null,
        argument: null,
        retry: null,
        name: r'notificationPermissionProvider',
        isAutoDispose: false,
        dependencies: null,
        $allTransitiveDependencies: null,
      );

  @override
  String debugGetCreateSourceHash() => _$notificationPermissionHash();

  @$internal
  @override
  $FutureProviderElement<PushPermission> $createElement(
    $ProviderPointer pointer,
  ) => $FutureProviderElement(pointer);

  @override
  FutureOr<PushPermission> create(Ref ref) {
    return notificationPermission(ref);
  }
}

String _$notificationPermissionHash() =>
    r'a04b09dfef5cab3f51a675fba61407a92e9889d5';

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

String _$mySubscriptionsHash() => r'a7276f9472a98f6ba65275fd1a1a0e6012119f7d';

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
