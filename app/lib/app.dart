import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import 'features/explore/explore_screen.dart';

final _router = GoRouter(
  routes: [GoRoute(path: '/', builder: (context, state) => const ExploreScreen())],
);

class PerimetreApp extends StatelessWidget {
  const PerimetreApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: 'Périmètre',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorSchemeSeed: const Color(0xFFC8102E), useMaterial3: true),
      routerConfig: _router,
    );
  }
}
