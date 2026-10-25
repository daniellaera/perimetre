import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../push/push_service.dart';
import '../explore/providers.dart';
import '../explore/widgets.dart';
import '../subscription/providers.dart';

/// Bottom navigation (Explorer / Mon périmètre) and handling of tapped pushes.
class HomeShell extends ConsumerStatefulWidget {
  const HomeShell({super.key, required this.shell});

  static const _perimetreBranch = 1;

  final StatefulNavigationShell shell;

  @override
  ConsumerState<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends ConsumerState<HomeShell> {
  StreamSubscription<Map<String, dynamic>>? _opened;

  @override
  void initState() {
    super.initState();
    final push = ref.read(pushServiceProvider);
    _opened = push.onOpened.listen(_onPushOpened);
    push.initialOpened().then((data) {
      if (data != null) _onPushOpened(data);
    });
  }

  @override
  void dispose() {
    _opened?.cancel();
    super.dispose();
  }

  /// Shows the "Mon périmètre" tab, refreshes its feed and, for a single-event push, opens the dossier.
  Future<void> _onPushOpened(Map<String, dynamic> data) async {
    if (!mounted) return;
    widget.shell.goBranch(HomeShell._perimetreBranch);
    ref.invalidate(recentEventsProvider);
    final idDossier = data['id_dossier'];
    if (idDossier is! String) return; // summary push: the feed is enough

    final messenger = ScaffoldMessenger.of(context);
    try {
      final subscription = await ref.read(mySubscriptionProvider.future);
      final dossier = subscription == null
          ? null
          : await findPushedDossier(ref.read(perimetreApiProvider), subscription, idDossier);
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
            label: 'Mon périmètre',
          ),
        ],
      ),
    );
  }
}
