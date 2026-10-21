import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app.dart';

void main() {
  runApp(
    // No automatic retries: a failed fetch should show its error, not silently loop.
    ProviderScope(retry: (retryCount, error) => null, child: const PerimetreApp()),
  );
}
