import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'app.dart';
import 'features/subscription/providers.dart';
import 'push/init_push.dart';
import 'push/push_service.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final prefs = await SharedPreferences.getInstance();
  final push = await initPush(prefs);
  runApp(
    ProviderScope(
      // No automatic retries: a failed fetch should show its error, not silently loop.
      retry: (retryCount, error) => null,
      overrides: [
        sharedPreferencesProvider.overrideWithValue(prefs),
        pushServiceProvider.overrideWithValue(push),
      ],
      child: const PerimetreApp(),
    ),
  );
}
