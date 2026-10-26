import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../api/models.dart';
import '../../api/perimetre_api.dart';
import '../../push/push_service.dart';
import '../explore/providers.dart';
import '../explore/widgets.dart';
import 'providers.dart';

/// "Mes périmètres" tab: the device's subscriptions (a form when there is none yet) and the recent events in
/// any of them.
class SubscriptionScreen extends ConsumerWidget {
  const SubscriptionScreen({super.key});

  /// Reloads the subscriptions (the device may have been deleted server-side), then the events.
  Future<void> _refresh(WidgetRef ref) async {
    try {
      ref.invalidate(mySubscriptionsProvider);
      await ref.read(recentEventsProvider.future);
    } catch (_) {
      // The error is shown in the list.
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(mySubscriptionsProvider);
    final subscriptions = async.value;

    final List<Widget> children;
    if (async.isLoading && !async.hasValue) {
      children = const [Padding(padding: EdgeInsets.all(32), child: Center(child: CircularProgressIndicator()))];
    } else if (async.hasError && !async.hasValue) {
      children = [
        _Message(
          'Impossible de charger vos périmètres.',
          action: TextButton(onPressed: () => ref.invalidate(mySubscriptionsProvider), child: const Text('Réessayer')),
        ),
      ];
    } else if (subscriptions == null || subscriptions.isEmpty) {
      children = [_SubscriptionForm(initial: null, first: true, onDone: () {})];
    } else {
      children = [
        for (final s in subscriptions) _SubscriptionCard(s),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
          child: OutlinedButton.icon(
            onPressed: () => openSubscriptionForm(context),
            icon: const Icon(Icons.add_location_alt_outlined),
            label: const Text('Ajouter un périmètre'),
          ),
        ),
        _RecentEvents(subscriptions),
      ];
    }

    return Scaffold(
      appBar: AppBar(title: const Text('Mes périmètres')),
      body: RefreshIndicator(
        onRefresh: () => _refresh(ref),
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

/// The add form (or the edit form for [initial]) as a full-screen page.
Future<void> openSubscriptionForm(BuildContext context, {Subscription? initial}) {
  return Navigator.of(context).push(
    MaterialPageRoute<void>(
      fullscreenDialog: true,
      builder: (context) => Scaffold(
        appBar: AppBar(title: Text(initial == null ? 'Nouveau périmètre' : 'Modifier le périmètre')),
        body: SingleChildScrollView(
          padding: const EdgeInsets.only(bottom: 24),
          child: _SubscriptionForm(initial: initial, first: false, onDone: () => Navigator.of(context).pop()),
        ),
      ),
    ),
  );
}

Future<void> _confirmDelete(BuildContext context, WidgetRef ref, Subscription s) async {
  final confirmed = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: const Text('Supprimer ce périmètre ?'),
      content: Text('Vous ne recevrez plus de notifications pour « ${s.name} ».'),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Annuler')),
        TextButton(onPressed: () => Navigator.pop(context, true), child: const Text('Supprimer')),
      ],
    ),
  );
  if (confirmed != true || !context.mounted) return;
  final messenger = ScaffoldMessenger.of(context);
  try {
    await ref.read(mySubscriptionsProvider.notifier).remove(s.id);
    messenger.showSnackBar(const SnackBar(content: Text('Périmètre supprimé.')));
  } on DioException {
    messenger.showSnackBar(const SnackBar(content: Text('Suppression impossible. Réessayez plus tard.')));
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
        style: TextStyle(fontSize: 12, color: Colors.black87),
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
// Form: name, address, radius, categories

class _SubscriptionForm extends ConsumerStatefulWidget {
  const _SubscriptionForm({required this.initial, required this.first, required this.onDone});

  /// The subscription being edited, or null for a new one.
  final Subscription? initial;

  /// The device has no subscription yet: the form is the whole tab and introduces the app.
  final bool first;
  final VoidCallback onDone;

  @override
  ConsumerState<_SubscriptionForm> createState() => _SubscriptionFormState();
}

class _SubscriptionFormState extends ConsumerState<_SubscriptionForm> {
  static const _suggestedNames = ['Domicile', 'Travail'];

  late final _name = TextEditingController(text: widget.initial?.label);
  late AddressSuggestion? _address = widget.initial == null
      ? (widget.first ? ref.read(selectedAddressProvider) : null)
      : AddressSuggestion(
          label: widget.initial!.address ?? 'Adresse enregistrée',
          lat: widget.initial!.lat,
          lon: widget.initial!.lng,
        );
  late int _radius = widget.initial?.radiusM ?? ref.read(radiusMetresProvider);
  late final Set<Groupe> _categories = {...(widget.initial?.categories ?? Groupe.notifiable)};
  bool _saving = false;

  bool get _valid => _address != null && _categories.isNotEmpty;

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final messenger = ScaffoldMessenger.of(context);
    final name = _name.text.trim();
    final input = SubscriptionInput(
      lat: _address!.lat,
      lng: _address!.lon,
      radiusM: _radius,
      categories: [for (final g in Groupe.notifiable) if (_categories.contains(g)) g],
      label: name.isEmpty ? null : name,
      address: widget.initial != null && _address!.label == 'Adresse enregistrée' ? null : _address!.label,
    );
    setState(() => _saving = true);
    try {
      final subscriptions = ref.read(mySubscriptionsProvider.notifier);
      if (widget.initial == null) {
        await subscriptions.add(input);
      } else {
        await subscriptions.edit(widget.initial!.id, input);
      }
      messenger.showSnackBar(
        SnackBar(
          content: Text(
            widget.first
                ? 'Notifications activées.'
                : widget.initial == null
                ? 'Périmètre ajouté.'
                : 'Périmètre mis à jour.',
          ),
        ),
      );
      widget.onDone();
    } on NotificationsDeniedException {
      messenger.showSnackBar(
        const SnackBar(content: Text('Notifications refusées. Autorisez-les dans les Réglages de l\'iPhone.')),
      );
    } on SubscriptionLimitException catch (e) {
      messenger.showSnackBar(SnackBar(content: Text(e.message)));
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
          if (widget.first) ...[
            Text(
              'Recevez une notification quand un dossier de construction apparaît ou change de statut près de chez '
              'vous. Vous pourrez ajouter d\'autres périmètres ensuite (travail, famille…).',
              style: text.bodyMedium,
            ),
            const SizedBox(height: 16),
          ],
          TextField(
            key: const Key('subscription-name'),
            controller: _name,
            maxLength: 40,
            textCapitalization: TextCapitalization.sentences,
            decoration: const InputDecoration(
              labelText: 'Nom (facultatif)',
              hintText: 'Domicile, Travail…',
              border: OutlineInputBorder(),
              isDense: true,
              counterText: '',
            ),
            onChanged: (_) => setState(() {}),
          ),
          const SizedBox(height: 4),
          Wrap(
            spacing: 8,
            children: [
              for (final name in _suggestedNames)
                ChoiceChip(
                  label: Text(name),
                  selected: _name.text.trim() == name,
                  onSelected: (on) => setState(() => _name.text = on ? name : ''),
                ),
            ],
          ),
          const SizedBox(height: 12),
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
              label: Text(
                widget.first
                    ? 'Activer les notifications'
                    : isNew
                    ? 'Ajouter ce périmètre'
                    : 'Enregistrer',
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// One subscription

class _SubscriptionCard extends ConsumerWidget {
  const _SubscriptionCard(this.subscription);

  final Subscription subscription;

  static IconData _icon(String? label) => switch (label?.toLowerCase()) {
    'domicile' => Icons.home_outlined,
    'travail' => Icons.work_outline,
    _ => Icons.place_outlined,
  };

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final s = subscription;
    final all = s.categories.length == Groupe.notifiable.length;
    return Card(
      margin: const EdgeInsets.fromLTRB(16, 12, 16, 0),
      child: ListTile(
        contentPadding: const EdgeInsets.only(left: 16, right: 4),
        leading: Icon(_icon(s.label)),
        title: Text(s.name, style: theme.textTheme.titleMedium),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (s.label != null && s.address != null) Text(s.address!),
            Text(
              'Rayon ${s.radiusM} m · ${all ? 'tous les statuts' : s.categories.map((g) => g.label).join(', ')}',
              style: TextStyle(color: theme.colorScheme.onSurfaceVariant),
            ),
          ],
        ),
        onTap: () => openSubscriptionForm(context, initial: s),
        trailing: PopupMenuButton<String>(
          tooltip: 'Actions',
          onSelected: (action) => action == 'edit'
              ? openSubscriptionForm(context, initial: s)
              : _confirmDelete(context, ref, s),
          itemBuilder: (context) => const [
            PopupMenuItem(value: 'edit', child: Text('Modifier')),
            PopupMenuItem(value: 'delete', child: Text('Supprimer')),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Recent events

class _RecentEvents extends ConsumerWidget {
  const _RecentEvents(this.subscriptions);

  final List<Subscription> subscriptions;

  /// With several perimeters, the name(s) of those an event matched.
  String? _place(DossierEvent e) {
    if (subscriptions.length < 2) return null;
    final names = {
      for (final id in e.subscriptionIds)
        for (final s in subscriptions)
          if (s.id == id) s.name,
    };
    return names.isEmpty ? null : names.join(', ');
  }

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
          const _Message(
            'Aucun changement pour l\'instant. Vous serez notifié dès qu\'un dossier bouge dans vos périmètres.',
          ),
        const Divider(height: 1),
        for (final e in events)
          DossierRow(e.dossier, event: e, place: _place(e), onTap: (d) => showDossierDetails(context, d)),
      ],
    );
  }
}
