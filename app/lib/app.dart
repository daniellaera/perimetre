import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

import 'features/explore/explore_screen.dart';
import 'features/home/home_shell.dart';
import 'features/subscription/subscription_screen.dart';

final _router = GoRouter(
  routes: [
    StatefulShellRoute.indexedStack(
      builder: (context, state, shell) => HomeShell(shell: shell),
      branches: [
        StatefulShellBranch(routes: [GoRoute(path: '/', builder: (context, state) => const ExploreScreen())]),
        StatefulShellBranch(
          routes: [GoRoute(path: '/perimetre', builder: (context, state) => const SubscriptionScreen())],
        ),
      ],
    ),
  ],
);

/// The app red (also the icon's background).
const _seed = Color(0xFFC8102E);

class PerimetreApp extends StatelessWidget {
  const PerimetreApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: 'Périmètre',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorSchemeSeed: _seed, useMaterial3: true),
      darkTheme: ThemeData(colorSchemeSeed: _seed, brightness: Brightness.dark, useMaterial3: true),
      routerConfig: _router,
    );
  }
}
