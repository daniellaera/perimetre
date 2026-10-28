import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../push/push_service.dart';
import '../explore/providers.dart';
import '../explore/widgets.dart';
import '../subscription/providers.dart';

/// Bottom navigation (Explorer / Mes périmètres), handling of tapped pushes, and the push token sync and
/// permission refresh at start and on every resume.
class HomeShell extends ConsumerStatefulWidget {
  const HomeShell({super.key, required this.shell});

  static const _perimetreBranch = 1;

  final StatefulNavigationShell shell;

  @override
  ConsumerState<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends ConsumerState<HomeShell> {
  StreamSubscription<Map<String, dynamic>>? _opened;
  late final AppLifecycleListener _lifecycle;

  @override
  void initState() {
    super.initState();
    ref.read(tokenSyncProvider).start();
    _lifecycle = AppLifecycleListener(
      onResume: () {
        // Notifications may have been switched on or off in the Settings app meanwhile.
        ref.invalidate(notificationPermissionProvider);
        ref.read(tokenSyncProvider).sync();
      },
    );
    final push = ref.read(pushServiceProvider);
    _opened = push.onOpened.listen(_onPushOpened);
    push.initialOpened().then((data) {
      if (data != null) _onPushOpened(data);
    });
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    _opened?.cancel();
    super.dispose();
  }

  /// Shows the "Mes périmètres" tab, refreshes its feed and, for a single-event push, opens the dossier.
  Future<void> _onPushOpened(Map<String, dynamic> data) async {
    if (!mounted) return;
    widget.shell.goBranch(HomeShell._perimetreBranch);
    ref.invalidate(recentEventsProvider);
    final idDossier = data['id_dossier'];
    if (idDossier is! String) return; // summary or test push: the feed is enough

    final messenger = ScaffoldMessenger.of(context);
    try {
      final subscriptions = await ref.read(mySubscriptionsProvider.future);
      final deviceId = ref.read(mySubscriptionsProvider.notifier).deviceId;
      // The push names the nearest matching subscription (pushes of the single-subscription backend do not).
      final subscriptionId = data['subscription_id'];
      final subscription =
          subscriptions.where((s) => s.id == subscriptionId).firstOrNull ?? subscriptions.firstOrNull;
      final dossier = deviceId == null
          ? null
          : await findPushedDossier(
              ref.read(perimetreApiProvider),
              deviceId: deviceId,
              subscription: subscription,
              idDossier: idDossier,
            );
      if (!mounted) return;
      if (dossier == null) {
        messenger.showSnackBar(const SnackBar(content: Text('Ce dossier n\'est plus disponible.')));
      } else {
        await showDossierDetails(context, dossier);
      }
    } catch (e) {
      messenger.showSnackBar(const SnackBar(content: Text('Impossible de charger le dossier.')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final shell = widget.shell;
    return Scaffold(
      body: shell,
      bottomNavigationBar: NavigationBar(
        selectedIndex: shell.currentIndex,
        onDestinationSelected: (i) => shell.goBranch(i, initialLocation: i == shell.currentIndex),
        destinations: const [
          NavigationDestination(icon: Icon(Icons.map_outlined), selectedIcon: Icon(Icons.map), label: 'Explorer'),
          NavigationDestination(
            icon: Icon(Icons.notifications_outlined),
            selectedIcon: Icon(Icons.notifications),
            label: 'Mes périmètres',
          ),
        ],
      ),
    );
  }
}
