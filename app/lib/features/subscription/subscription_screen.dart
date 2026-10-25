import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../api/models.dart';
import '../../push/push_service.dart';
import '../explore/providers.dart';
import '../explore/widgets.dart';
import 'providers.dart';

/// "Mon périmètre" tab: the subscription (form or summary card) and the recent events in its perimeter.
class SubscriptionScreen extends ConsumerStatefulWidget {
  const SubscriptionScreen({super.key});

  @override
  ConsumerState<SubscriptionScreen> createState() => _SubscriptionScreenState();
}

class _SubscriptionScreenState extends ConsumerState<SubscriptionScreen> {
  bool _editing = false;

  /// Reloads the subscription (it may have been deleted server-side), then its events.
  Future<void> _refresh() async {
    try {
      ref.invalidate(mySubscriptionProvider);
      await ref.read(recentEventsProvider.future);
    } catch (_) {
      // The error is shown in the list.
    }
  }

  Future<void> _deactivate() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Désactiver les notifications ?'),
        content: const Text('Vous ne recevrez plus de notifications pour ce périmètre.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Annuler')),
          TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Désactiver')),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    final messenger = ScaffoldMessenger.of(context);
    try {
      await ref.read(mySubscriptionProvider.notifier).deactivate();
      messenger.showSnackBar(const SnackBar(content: Text('Notifications désactivées.')));
    } on DioException {
      messenger.showSnackBar(const SnackBar(content: Text('Impossible de désactiver. Réessayez plus tard.')));
    }
  }

  @override
  Widget build(BuildContext context) {
    final async = ref.watch(mySubscriptionProvider);
    final subscription = async.value;

    final List<Widget> children;
    if (async.isLoading && !async.hasValue) {
      children = const [Padding(padding: EdgeInsets.all(32), child: Center(child: CircularProgressIndicator()))];
    } else if (async.hasError && !async.hasValue) {
      children = [
        _Message(
          'Impossible de charger votre périmètre.',
          action: TextButton(onPressed: () => ref.invalidate(mySubscriptionProvider), child: const Text('Réessayer')),
        ),
      ];
    } else if (subscription == null || _editing) {
      children = [
        _SubscriptionForm(
          initial: subscription,
          onDone: () => setState(() => _editing = false),
          onCancel: subscription == null ? null : () => setState(() => _editing = false),
        ),
      ];
    } else {
      children = [
        _SubscriptionCard(subscription, onEdit: () => setState(() => _editing = true), onDeactivate: _deactivate),
        const _RecentEvents(),
      ];
    }

    return Scaffold(
      appBar: AppBar(title: const Text('Mon périmètre')),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.only(bottom: 24),
          children: [
            if (!ref.watch(pushServiceProvider).isConfigured) const _DevModeBanner(),
            ...children,
          ],
        ),
      ),
    );
  }
}

class _DevModeBanner extends StatelessWidget {
  const _DevModeBanner();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      color: Colors.amber.shade100,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: const Text(
        'Mode développement : Firebase n\'est pas configuré, les notifications sont simulées (jeton factice).',
        style: TextStyle(fontSize: 12),
      ),
    );
  }
}

class _Message extends StatelessWidget {
  const _Message(this.text, {this.action});

  final String text;
  final Widget? action;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
      child: Row(
        children: [
          Expanded(child: Text(text)),
          ?action,
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Form: address, radius, categories

class _SubscriptionForm extends ConsumerStatefulWidget {
  const _SubscriptionForm({required this.initial, required this.onDone, required this.onCancel});

  /// The subscription being edited, or null for a new one.
  final Subscription? initial;
  final VoidCallback onDone;
  final VoidCallback? onCancel;

  @override
  ConsumerState<_SubscriptionForm> createState() => _SubscriptionFormState();
}

class _SubscriptionFormState extends ConsumerState<_SubscriptionForm> {
  late AddressSuggestion? _address = widget.initial == null
      ? ref.read(selectedAddressProvider)
      : AddressSuggestion(
          label: widget.initial!.label ?? 'Adresse enregistrée',
          lat: widget.initial!.lat,
          lon: widget.initial!.lng,
        );
  late int _radius = widget.initial?.radiusM ?? ref.read(radiusMetresProvider);
  late final Set<Groupe> _categories = {...(widget.initial?.categories ?? Groupe.notifiable)};
  bool _saving = false;

  bool get _valid => _address != null && _categories.isNotEmpty;

  Future<void> _save() async {
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _saving = true);
    try {
      await ref
          .read(mySubscriptionProvider.notifier)
          .save(
            address: _address!,
            radiusM: _radius,
            categories: [for (final g in Groupe.notifiable) if (_categories.contains(g)) g],
          );
      messenger.showSnackBar(
        SnackBar(content: Text(widget.initial == null ? 'Notifications activées.' : 'Périmètre mis à jour.')),
      );
      widget.onDone();
    } on NotificationsDeniedException {
      messenger.showSnackBar(
        const SnackBar(content: Text('Notifications refusées. Autorisez-les dans les Réglages de l\'iPhone.')),
      );
    } catch (e) {
      debugPrint('Subscription save failed: $e');
      messenger.showSnackBar(const SnackBar(content: Text('Enregistrement impossible. Réessayez plus tard.')));
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final isNew = widget.initial == null;
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (isNew) ...[
            Text(
              'Recevez une notification quand un dossier de construction apparaît ou change de statut près de chez vous.',
              style: text.bodyMedium,
            ),
            const SizedBox(height: 16),
          ],
          AddressField(initialText: _address?.label, onSelected: (a) => setState(() => _address = a)),
          const SizedBox(height: 8),
          RadiusSlider(value: _radius, onChangeEnd: (r) => setState(() => _radius = r)),
          const SizedBox(height: 8),
          Text('Me prévenir quand un dossier est…', style: text.titleSmall),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 4,
            children: [
              for (final g in Groupe.notifiable)
                FilterChip(
                  label: Text(g.label),
                  avatar: CircleAvatar(radius: 6, backgroundColor: groupeColor(g)),
                  selected: _categories.contains(g),
                  showCheckmark: false,
                  onSelected: (on) => setState(() => on ? _categories.add(g) : _categories.remove(g)),
                ),
            ],
          ),
          if (_categories.isEmpty)
            Padding(
              padding: const EdgeInsets.only(top: 4),
              child: Text(
                'Choisissez au moins une catégorie.',
                style: text.bodySmall?.copyWith(color: Theme.of(context).colorScheme.error),
              ),
            ),
          const SizedBox(height: 16),
          SizedBox(
            width: double.infinity,
            child: FilledButton.icon(
              onPressed: _valid && !_saving ? _save : null,
              icon: _saving
                  ? const SizedBox.square(dimension: 18, child: CircularProgressIndicator(strokeWidth: 2))
                  : Icon(isNew ? Icons.notifications_active : Icons.check),
              label: Text(isNew ? 'Activer les notifications' : 'Enregistrer'),
            ),
          ),
          if (widget.onCancel != null)
            SizedBox(
              width: double.infinity,
              child: TextButton(onPressed: _saving ? null : widget.onCancel, child: const Text('Annuler')),
            ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Current subscription

class _SubscriptionCard extends StatelessWidget {
  const _SubscriptionCard(this.subscription, {required this.onEdit, required this.onDeactivate});

  final Subscription subscription;
  final VoidCallback onEdit;
  final VoidCallback onDeactivate;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final s = subscription;
    final all = s.categories.length == Groupe.notifiable.length;
    return Card(
      margin: const EdgeInsets.fromLTRB(16, 16, 16, 0),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.notifications_active),
                const SizedBox(width: 8),
                Expanded(child: Text('Notifications actives', style: text.titleMedium)),
              ],
            ),
            const SizedBox(height: 12),
            Text(s.label ?? 'Adresse enregistrée', style: text.bodyLarge),
            const SizedBox(height: 4),
            Text(
              'Rayon ${s.radiusM} m · ${all ? 'tous les statuts' : s.categories.map((g) => g.label).join(', ')}',
              style: text.bodyMedium?.copyWith(color: Colors.black54),
            ),
            const SizedBox(height: 8),
            Row(
              mainAxisAlignment: MainAxisAlignment.end,
              children: [
                TextButton(onPressed: onDeactivate, child: const Text('Désactiver')),
                const SizedBox(width: 8),
                OutlinedButton(onPressed: onEdit, child: const Text('Modifier')),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Recent events

class _RecentEvents extends ConsumerWidget {
  const _RecentEvents();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(recentEventsProvider);
    final events = async.value ?? const <DossierEvent>[];
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 24, 16, 8),
          child: Text('Récent', style: Theme.of(context).textTheme.titleMedium),
        ),
        if (async.isLoading && !async.hasValue)
          const LinearProgressIndicator()
        else if (async.hasError && !async.hasValue)
          _Message(
            'Impossible de charger les changements récents.',
            action: TextButton(onPressed: () => ref.invalidate(recentEventsProvider), child: const Text('Réessayer')),
          )
        else if (events.isEmpty)
          const _Message('Aucun changement pour l\'instant. Vous serez notifié dès qu\'un dossier bouge dans votre périmètre.'),
        const Divider(height: 1),
        for (final e in events)
          DossierRow(e.dossier, event: e, onTap: (d) => showDossierDetails(context, d)),
      ],
    );
  }
}
