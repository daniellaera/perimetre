import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:perimetre/api/models.dart';
import 'package:perimetre/api/perimetre_api.dart';
import 'package:perimetre/push/push_service.dart';
import 'package:perimetre/push/token_sync.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// [token] null = APNs has not delivered the device token yet.
class FakePush implements PushService {
  String? token;
  final refreshes = StreamController<String>.broadcast();

  @override
  bool get isConfigured => true;

  @override
  String get platform => 'ios';

  @override
  Future<PushPermission> permission() async => PushPermission.granted;

  @override
  Future<PushPermission> requestPermission() async => PushPermission.granted;

  @override
  Future<String?> currentToken() async => token;

  @override
  Stream<String> get onTokenRefresh => refreshes.stream;

  @override
  Stream<Map<String, dynamic>> get onOpened => const Stream.empty();

  @override
  Future<Map<String, dynamic>?> initialOpened() async => null;
}

/// Records PUT /devices/{id}; [failures] calls throw first, [deviceExists] false answers 404.
class FakeApi extends PerimetreApi {
  FakeApi() : super(Dio());

  final puts = <String>[];
  int failures = 0;
  bool deviceExists = true;

  @override
  Future<Device?> updateDevice(String id, {required String pushToken, required String platform}) async {
    if (failures > 0) {
      failures--;
      throw DioException(requestOptions: RequestOptions(path: '/devices/$id'));
    }
    puts.add(pushToken);
    return deviceExists ? const Device(id: 'device-1', subscriptionCount: 1, maxSubscriptions: 25) : null;
  }
}

const _retry = Duration(milliseconds: 20);

Future<void> _settle() => Future<void>.delayed(_retry * 4);

void main() {
  late SharedPreferences prefs;
  late FakePush push;
  late FakeApi api;
  String? deviceId;

  TokenSync newSync() =>
      TokenSync(prefs: prefs, api: api, push: push, deviceId: () => deviceId, retryDelays: const [_retry]);

  setUp(() async {
    SharedPreferences.setMockInitialValues({});
    prefs = await SharedPreferences.getInstance();
    push = FakePush();
    api = FakeApi();
    deviceId = 'device-1';
  });

  test('sends the current token once, then only when it changes', () async {
    push.token = 'token-a';
    final sync = newSync()..start();
    await _settle();
    await sync.sync(); // e.g. a resume with the same token
    expect(api.puts, ['token-a']);
    expect(prefs.getString(TokenSync.sentTokenKey), 'token-a');

    push.token = 'token-b';
    push.refreshes.add('token-b');
    await _settle();
    expect(api.puts, ['token-a', 'token-b']);
    sync.dispose();
  });

  test('never sends a token before APNs is ready, and retries until there is one', () async {
    final sync = newSync()..start();
    await _settle();
    expect(api.puts, isEmpty);

    push.token = 'mapped-token';
    await _settle();
    expect(api.puts, ['mapped-token']);
    sync.dispose();
  });

  test('a refresh event is only a hint: the token sent is the current one', () async {
    final sync = newSync()..start();
    push.refreshes.add('fetched-before-apns'); // what FCM announces before APNs, while currentToken is null
    await _settle();
    expect(api.puts, isEmpty);
    sync.dispose();
  });

  test('retries after a network error', () async {
    push.token = 'token-a';
    api.failures = 2;
    final sync = newSync()..start();
    await _settle();
    await _settle();
    expect(api.puts, ['token-a']);
    expect(prefs.getString(TokenSync.sentTokenKey), 'token-a');
    sync.dispose();
  });

  test('does nothing without a device, and does not record a token the backend refused (404)', () async {
    push.token = 'token-a';
    deviceId = null;
    final sync = newSync();
    await sync.sync();
    expect(api.puts, isEmpty);

    deviceId = 'device-1';
    api.deviceExists = false;
    await sync.sync();
    expect(api.puts, ['token-a']);
    expect(prefs.getString(TokenSync.sentTokenKey), isNull);
    sync.dispose();
  });

  test('registration waits for a usable token, else PushNotReadyException', () async {
    final sync = newSync();
    expect(sync.tokenForRegistration(timeout: Duration.zero), throwsA(isA<PushNotReadyException>()));

    final waiting = sync.tokenForRegistration(timeout: const Duration(seconds: 5));
    await Future<void>.delayed(const Duration(milliseconds: 100));
    push.token = 'mapped-token';
    expect(await waiting, 'mapped-token');
    sync.dispose();
  });
}
