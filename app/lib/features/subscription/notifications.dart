import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../api/perimetre_api.dart';
import '../../push/push_service.dart';
import '../explore/providers.dart';
import 'providers.dart';

/// When the permission is not determined yet: an explanation page, then (unless it is closed) the OS prompt.
/// Takes the navigator and the container rather than a context, so it can run after the calling form is gone
/// (the first perimeter's form is replaced by the list as soon as the perimeter is saved).
Future<PushPermission> askNotificationPermission(NavigatorState navigator, ProviderContainer container) async {
  final push = container.read(pushServiceProvider);
  var permission = await push.permission();
  if (permission == PushPermission.notDetermined) {
    final proceed = await navigator.push<bool>(
      MaterialPageRoute(fullscreenDialog: true, builder: (context) => const NotificationPrimerPage()),
    );
    if (proceed == true) permission = await push.requestPermission();
  }
  container.invalidate(notificationPermissionProvider);
  return permission;
}

/// The app's page in the Settings app, where notifications are switched on.
Future<void> openNotificationSettings() async {
  try {
    await launchUrl(Uri.parse('app-settings:'));
  } catch (e) {
    debugPrint('Settings not opened: $e');
  }
}

/// Shown before the OS prompt: what the notifications are for, and that refusing them means no alerts.
class NotificationPrimerPage extends StatelessWidget {
  const NotificationPrimerPage({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(Icons.notifications_active_outlined, size: 56, color: theme.colorScheme.primary),
              const SizedBox(height: 24),
              Text('Recevoir les alertes', style: theme.textTheme.headlineSmall),
              const SizedBox(height: 16),
              Text(
                'Périmètre vous prévient quand un dossier de construction est déposé, autorisé, contesté ou refusé '
                'dans vos périmètres, et quand les travaux commencent.',
                style: theme.textTheme.bodyLarge,
              ),
              const SizedBox(height: 12),
              Text(
                'L\'iPhone va vous demander l\'autorisation d\'envoyer des notifications. Choisissez « Autoriser » : '
                'sans elle, aucune alerte n\'arrivera.',
                style: theme.textTheme.bodyLarge,
              ),
              const Spacer(),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: () => Navigator.of(context).pop(true),
                  child: const Text('Continuer'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// In each perimeter's card while notifications are off: no alert will arrive, and how to fix it.
class NotificationsOffNotice extends ConsumerWidget {
  const NotificationsOffNotice({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final permission = ref.watch(notificationPermissionProvider).value;
    if (permission == null || permission == PushPermission.granted) return const SizedBox.shrink();
    final theme = Theme.of(context);
    final denied = permission == PushPermission.denied;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 8, 8),
      child: Row(
        children: [
          Icon(Icons.notifications_off_outlined, size: 20, color: theme.colorScheme.error),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              denied
                  ? 'Notifications désactivées : aucune alerte n\'arrivera.'
                  : 'Notifications pas encore autorisées : aucune alerte n\'arrivera.',
              style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.error),
            ),
          ),
          TextButton(
            onPressed: denied
                ? openNotificationSettings
                : () => askNotificationPermission(Navigator.of(context), ProviderScope.containerOf(context)),
            child: Text(denied ? 'Ouvrir les Réglages' : 'Autoriser'),
          ),
        ],
      ),
    );
  }
}

/// "Tester les notifications": makes sure the backend has the current token, then asks it for a test push.
Future<void> testNotifications(BuildContext context, WidgetRef ref) async {
  final messenger = ScaffoldMessenger.of(context);
  final deviceId = ref.read(mySubscriptionsProvider.notifier).deviceId;
  final api = ref.read(perimetreApiProvider);
  final tokenSync = ref.read(tokenSyncProvider);
  if (deviceId == null) return;
  if (await ref.read(pushServiceProvider).permission() != PushPermission.granted) {
    ref.invalidate(notificationPermissionProvider);
    messenger.showSnackBar(
      const SnackBar(
        content: Text('Les notifications sont désactivées pour Périmètre : la notification de test ne s\'afficherait pas.'),
        action: SnackBarAction(label: 'Réglages', onPressed: openNotificationSettings),
        persist: false,
      ),
    );
    return;
  }
  String text;
  try {
    await tokenSync.sync();
    switch (await api.testPush(deviceId)) {
      case TestPushStatus.sent:
        text = 'Notification de test envoyée : elle devrait s\'afficher dans quelques secondes.';
      case TestPushStatus.invalidToken:
        // Resend the current token on the next pass, in case the backend's copy is the stale one.
        await tokenSync.forget();
        await tokenSync.sync();
        text = 'Cet appareil n\'était pas joignable. Réessayez dans une minute.';
      case TestPushStatus.failed:
        text = 'Envoi impossible. Réessayez plus tard.';
      case null:
        ref.invalidate(mySubscriptionsProvider);
        text = 'Cet appareil n\'est plus enregistré. Ajoutez à nouveau un périmètre.';
    }
  } on TestPushRateLimitedException catch (e) {
    text = e.message;
  } on DioException {
    text = 'Envoi impossible. Vérifiez la connexion Internet.';
  }
  messenger.showSnackBar(SnackBar(content: Text(text)));
}
