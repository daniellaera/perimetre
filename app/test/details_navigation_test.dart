// The dossier details sheet must always be leavable: a visible "Fermer" button pinned in its header (also when the
// content is long and scrolled, on a small screen, with large text), swipe-down on the header, the system back
// (Android's button), a tap on the scrim. Regression test for the 1.1.0 (6) build, whose sheet had none of these
// visible. The shell/bottom-bar case is covered by integration_test/navigation_test.dart.
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:perimetre/api/models.dart';
import 'package:perimetre/features/explore/widgets.dart';

import 'parcelles_test.dart' show batiment, dossierJson, richParcel, zone;

Dossier longDossier() => Dossier.fromJson(
  dossierJson({
    'description': 'Construction de plusieurs immeubles avec parking souterrain et panneaux solaires en toiture',
    'flags': ['logements', 'parking'],
    'parcelles': [
      for (var i = 1; i <= 3; i++)
        richParcel(
          i,
          batiments: [for (var b = 0; b < 30; b++) batiment('B$b')],
          restrictions: [
            zone(),
            zone(type: 'ZONE_DEVELOPPEMENT', pct: 40),
          ],
          nb: 40,
        ),
    ],
  }),
);

Future<void> pumpHost(WidgetTester tester, Dossier d, {Size size = const Size(375, 667), double textScale = 1}) async {
  tester.view.physicalSize = size * 2;
  tester.view.devicePixelRatio = 2;
  tester.platformDispatcher.textScaleFactorTestValue = textScale;
  addTearDown(tester.view.reset);
  addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
  // The real app's shape: a shell (here with a bottom bar) inside a router; the sheet must cover the bar.
  final router = GoRouter(
    routes: [
      ShellRoute(
        builder: (context, state, child) => Scaffold(
          body: child,
          bottomNavigationBar: NavigationBar(
            destinations: const [
              NavigationDestination(icon: Icon(Icons.map), label: 'Explorer'),
              NavigationDestination(icon: Icon(Icons.notifications), label: 'Mes périmètres'),
            ],
          ),
        ),
        routes: [
          GoRoute(
            path: '/',
            builder: (context, state) => Center(
              child: TextButton(onPressed: () => showDossierDetails(context, d), child: const Text('open')),
            ),
          ),
        ],
      ),
    ],
  );
  await tester.pumpWidget(MaterialApp.router(routerConfig: router));
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

void expectCloseButtonReachable(WidgetTester tester) {
  final fermer = find.ancestor(of: find.text('Fermer'), matching: find.byType(TextButton));
  expect(fermer, findsOneWidget);
  final rect = tester.getRect(fermer);
  final screen = tester.view.physicalSize / tester.view.devicePixelRatio;
  expect(rect.top, greaterThanOrEqualTo(0));
  expect(rect.bottom, lessThanOrEqualTo(screen.height));
  expect(rect.height, greaterThanOrEqualTo(44));
  expect(rect.width, greaterThanOrEqualTo(44));
  // Hit-testable, not covered by anything.
  expect(tester.getRect(find.byType(BottomSheet)).top, greaterThanOrEqualTo(0));
}

void main() {
  testWidgets('Fermer is visible and closes the sheet back to the screen below', (tester) async {
    await pumpHost(tester, longDossier());
    expectCloseButtonReachable(tester);
    await tester.tap(find.text('Fermer'));
    await tester.pumpAndSettle();
    expect(find.byType(BottomSheet), findsNothing);
    expect(find.text('open'), findsOneWidget);
  });

  testWidgets('the sheet covers the bottom bar and keeps Fermer while the long content is scrolled', (tester) async {
    await pumpHost(tester, longDossier());
    final bar = tester.getRect(find.byType(NavigationBar));
    expect(tester.getRect(find.byType(BottomSheet)).bottom, greaterThanOrEqualTo(bar.bottom));
    for (final dy in [-300.0, -3000.0, 3000.0]) {
      await tester.drag(
        find.descendant(of: find.byType(BottomSheet), matching: find.byType(SingleChildScrollView)),
        Offset(0, dy),
      );
      await tester.pumpAndSettle();
      expectCloseButtonReachable(tester);
    }
  });

  testWidgets('small screen, large text: no overflow, Fermer reachable, every section expands', (tester) async {
    await pumpHost(tester, longDossier(), size: const Size(320, 568), textScale: 2.0);
    expectCloseButtonReachable(tester);
    for (final tile in find.byType(ExpansionTile).evaluate().toList()) {
      await tester.ensureVisible(find.byWidget(tile.widget));
      await tester.tap(find.descendant(of: find.byWidget(tile.widget), matching: find.byType(ListTile)));
      await tester.pumpAndSettle();
      expectCloseButtonReachable(tester);
    }
    expect(tester.takeException(), isNull); // overflow errors are reported as exceptions
    await tester.tap(find.text('Fermer'));
    await tester.pumpAndSettle();
    expect(find.byType(BottomSheet), findsNothing);
  });

  testWidgets('swiping the header down closes the sheet', (tester) async {
    await pumpHost(tester, longDossier());
    await tester.drag(find.text('Détail du dossier'), const Offset(0, 600));
    await tester.pumpAndSettle();
    expect(find.byType(BottomSheet), findsNothing);
  });

  testWidgets('the system back (Android) closes the sheet, not the app', (tester) async {
    await pumpHost(tester, longDossier());
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.byType(BottomSheet), findsNothing);
    expect(find.text('open'), findsOneWidget);
  });

  testWidgets('a tap on the scrim above the sheet closes it', (tester) async {
    await pumpHost(tester, longDossier());
    await tester.tapAt(const Offset(187, 10));
    await tester.pumpAndSettle();
    expect(find.byType(BottomSheet), findsNothing);
  });

  testWidgets('a dossier without parcels has the same way out', (tester) async {
    await pumpHost(tester, Dossier.fromJson(dossierJson()));
    expectCloseButtonReachable(tester);
    await tester.tap(find.text('Fermer'));
    await tester.pumpAndSettle();
    expect(find.byType(BottomSheet), findsNothing);
  });
}
