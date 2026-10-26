import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:latlong2/latlong.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../api/models.dart';
import '../../map/map_tiles.dart';
import 'providers.dart';

enum ListStatus { idle, loading, error, data }

Color groupeColor(Groupe groupe) => switch (groupe) {
  Groupe.depose => Colors.orange.shade700,
  Groupe.autorise => Colors.green.shade600,
  Groupe.chantier => Colors.blue.shade600,
  Groupe.recours => Colors.purple.shade500,
  Groupe.refuse => Colors.grey.shade600,
  Groupe.autre => Colors.black45,
};

IconData flagIcon(String flag) => switch (flag) {
  'arbres' => Icons.park,
  'logements' => Icons.apartment,
  'parking' => Icons.local_parking,
  'piscine' => Icons.pool,
  'antenne' => Icons.cell_tower,
  'surelevation' => Icons.height,
  'plq' => Icons.map_outlined,
  _ => Icons.label_outline,
};

/// Flags shown as icons in list rows; the others appear only in the details sheet.
const _rowFlags = {'arbres', 'logements', 'parking', 'piscine', 'antenne'};

String formatDate(DateTime d) =>
    '${d.day.toString().padLeft(2, '0')}.${d.month.toString().padLeft(2, '0')}.${d.year}';

String truncate(String text, int maxChars) =>
    text.length <= maxChars ? text : '${text.substring(0, maxChars).trimRight()}…';

// ---------------------------------------------------------------------------
// Address field with geo.admin.ch suggestions

class AddressField extends ConsumerStatefulWidget {
  const AddressField({super.key, required this.onSelected, this.initialText});

  final ValueChanged<AddressSuggestion> onSelected;
  final String? initialText;

  @override
  ConsumerState<AddressField> createState() => _AddressFieldState();
}

class _AddressFieldState extends ConsumerState<AddressField> {
  static const _debounce = Duration(milliseconds: 300);

  int _requestId = 0;
  Iterable<AddressSuggestion> _last = const [];
  bool _failed = false;

  Future<Iterable<AddressSuggestion>> _options(TextEditingValue value) async {
    final query = value.text.trim();
    if (query.length < 3) return const [];
    final id = ++_requestId;
    await Future<void>.delayed(_debounce);
    // Superseded by a newer keystroke; Autocomplete ignores stale results anyway.
    if (id != _requestId) return _last;
    try {
      _last = await ref.read(geoAdminProvider).search(query);
      if (mounted && _failed) setState(() => _failed = false);
    } on DioException {
      _last = const [];
      if (mounted) setState(() => _failed = true);
    }
    return _last;
  }

  @override
  Widget build(BuildContext context) {
    return Autocomplete<AddressSuggestion>(
      optionsBuilder: _options,
      initialValue: widget.initialText == null ? null : TextEditingValue(text: widget.initialText!),
      displayStringForOption: (a) => a.label,
      onSelected: (a) {
        FocusScope.of(context).unfocus();
        widget.onSelected(a);
      },
      fieldViewBuilder: (context, controller, focusNode, onSubmitted) => TextField(
        controller: controller,
        focusNode: focusNode,
        onSubmitted: (_) => onSubmitted(),
        textInputAction: TextInputAction.search,
        autocorrect: false,
        decoration: InputDecoration(
          hintText: 'Adresse à Genève',
          prefixIcon: const Icon(Icons.search),
          border: const OutlineInputBorder(),
          isDense: true,
          errorText: _failed ? 'Recherche d\'adresse indisponible' : null,
          suffixIcon: IconButton(
            icon: const Icon(Icons.clear),
            tooltip: 'Effacer',
            onPressed: controller.clear,
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Radius slider: updates its label live, commits (and refetches) on release.

class RadiusSlider extends StatefulWidget {
  const RadiusSlider({super.key, required this.value, required this.onChangeEnd});

  final int value;
  final ValueChanged<int> onChangeEnd;

  @override
  State<RadiusSlider> createState() => _RadiusSliderState();
}

class _RadiusSliderState extends State<RadiusSlider> {
  late double _value = widget.value.toDouble();

  @override
  void didUpdateWidget(RadiusSlider old) {
    super.didUpdateWidget(old);
    if (old.value != widget.value) _value = widget.value.toDouble();
  }

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        const Text('Rayon'),
        Expanded(
          child: Slider(
            value: _value,
            min: minRadius.toDouble(),
            max: maxRadius.toDouble(),
            divisions: (maxRadius - minRadius) ~/ 50,
            label: '${_value.round()} m',
            onChanged: (v) => setState(() => _value = v),
            onChangeEnd: (v) => widget.onChangeEnd(v.round()),
          ),
        ),
        SizedBox(width: 64, child: Text('${_value.round()} m', textAlign: TextAlign.end)),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Map

class DossierMap extends StatefulWidget {
  const DossierMap({
    super.key,
    required this.center,
    required this.radius,
    required this.dossiers,
    required this.bottomInsetFraction,
    required this.onTapDossier,
  });

  final AddressSuggestion? center;
  final int radius;
  final List<Dossier> dossiers;

  /// Fraction of the map hidden by the bottom sheet, so the circle is fitted above it.
  final double bottomInsetFraction;
  final ValueChanged<Dossier> onTapDossier;

  @override
  State<DossierMap> createState() => _DossierMapState();
}

class _DossierMapState extends State<DossierMap> {
  static const _geneva = LatLng(46.2044, 6.1432);

  final _controller = MapController();
  double _height = 0;

  LatLng? get _center => widget.center == null ? null : LatLng(widget.center!.lat, widget.center!.lon);

  @override
  void didUpdateWidget(DossierMap old) {
    super.didUpdateWidget(old);
    if (old.center != widget.center || old.radius != widget.radius) _fitCircle();
  }

  void _fitCircle() {
    final center = _center;
    if (center == null) return;
    const distance = Distance();
    final bounds = LatLngBounds.fromPoints([
      for (final bearing in [0, 90, 180, 270]) distance.offset(center, widget.radius, bearing),
    ]);
    _controller.fitCamera(
      CameraFit.bounds(
        bounds: bounds,
        padding: EdgeInsets.fromLTRB(24, 24, 24, 24 + _height * widget.bottomInsetFraction),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final center = _center;
    return LayoutBuilder(
      builder: (context, constraints) {
        _height = constraints.maxHeight;
        return FlutterMap(
          mapController: _controller,
          options: MapOptions(
            initialCenter: _geneva,
            initialZoom: 13,
            // Shown until the tiles arrive.
            backgroundColor: Theme.of(context).colorScheme.surfaceContainerHighest,
          ),
          children: [
            baseTileLayer(Theme.of(context).brightness),
            if (center != null)
              CircleLayer(
                circles: [
                  CircleMarker(
                    point: center,
                    radius: widget.radius.toDouble(),
                    useRadiusInMeter: true,
                    color: Theme.of(context).colorScheme.primary.withValues(alpha: 0.12),
                    borderColor: Theme.of(context).colorScheme.primary.withValues(alpha: 0.7),
                    borderStrokeWidth: 2,
                  ),
                ],
              ),
            MarkerLayer(
              markers: [
                for (final d in widget.dossiers)
                  Marker(
                    point: LatLng(d.lat, d.lng),
                    width: 22,
                    height: 22,
                    child: GestureDetector(
                      onTap: () => widget.onTapDossier(d),
                      child: DecoratedBox(
                        decoration: BoxDecoration(
                          color: groupeColor(d.groupe),
                          shape: BoxShape.circle,
                          border: Border.all(color: Colors.white, width: 2),
                          boxShadow: const [BoxShadow(blurRadius: 2, color: Colors.black26)],
                        ),
                      ),
                    ),
                  ),
                if (center != null)
                  Marker(
                    point: center,
                    width: 36,
                    height: 36,
                    alignment: Alignment.topCenter,
                    child: Icon(Icons.place, size: 36, color: Theme.of(context).colorScheme.onSurface),
                  ),
              ],
            ),
            const MapAttribution(),
          ],
        );
      },
    );
  }
}

// ---------------------------------------------------------------------------
// Bottom list

class DossierSheet extends StatelessWidget {
  const DossierSheet({
    super.key,
    required this.initialSize,
    required this.status,
    required this.radius,
    required this.dossiers,
    required this.onRetry,
    required this.onTapDossier,
  });

  /// The backend caps /dossiers at this many results.
  static const _backendLimit = 200;

  final double initialSize;
  final ListStatus status;
  final int radius;
  final List<Dossier> dossiers;
  final VoidCallback onRetry;
  final ValueChanged<Dossier> onTapDossier;

  @override
  Widget build(BuildContext context) {
    return DraggableScrollableSheet(
      initialChildSize: initialSize,
      minChildSize: 0.12,
      maxChildSize: 0.9,
      builder: (context, scrollController) => Material(
        elevation: 8,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
        clipBehavior: Clip.antiAlias,
        child: ListView.builder(
          controller: scrollController,
          padding: EdgeInsets.only(bottom: MediaQuery.paddingOf(context).bottom),
          itemCount: dossiers.length + 1,
          itemBuilder: (context, i) => i == 0 ? _header(context) : DossierRow(dossiers[i - 1], onTap: onTapDossier),
        ),
      ),
    );
  }

  Widget _header(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final Widget body = switch (status) {
      ListStatus.idle => const Text('Saisissez une adresse à Genève pour voir les dossiers autour.'),
      ListStatus.loading => const Text('Chargement des dossiers…'),
      ListStatus.error => Row(
        children: [
          const Expanded(child: Text('Impossible de charger les dossiers.')),
          TextButton(onPressed: onRetry, child: const Text('Réessayer')),
        ],
      ),
      ListStatus.data when dossiers.isEmpty => Text('Aucun dossier dans un rayon de $radius m.'),
      ListStatus.data => Text(
        '${dossiers.length} dossier${dossiers.length > 1 ? 's' : ''} dans un rayon de $radius m'
        '${dossiers.length >= _backendLimit ? ' (maximum affiché)' : ''}',
        style: text.titleSmall,
      ),
    };
    return Column(
      children: [
        Container(
          margin: const EdgeInsets.symmetric(vertical: 8),
          width: 36,
          height: 4,
          decoration: BoxDecoration(
            color: Theme.of(context).colorScheme.outlineVariant,
            borderRadius: BorderRadius.circular(2),
          ),
        ),
        Padding(padding: const EdgeInsets.fromLTRB(16, 0, 16, 8), child: body),
        if (status == ListStatus.loading) const LinearProgressIndicator(),
        const Divider(height: 1),
      ],
    );
  }
}

class DossierRow extends StatelessWidget {
  const DossierRow(this.dossier, {super.key, required this.onTap, this.event, this.place});

  final Dossier dossier;
  final ValueChanged<Dossier> onTap;

  /// When set (subscription feed), the event text leads the row and sets the marker colour.
  final DossierEvent? event;

  /// Name(s) of the matched perimeter(s), appended to the event line (feeds of several perimeters).
  final String? place;

  @override
  Widget build(BuildContext context) {
    final d = dossier;
    final depot = d.dateDepot == null ? '' : ' · déposé le ${formatDate(d.dateDepot!)}';
    final flags = d.flags.where(_rowFlags.contains);
    // When the description is the main text, the operation label adds information, unless it is the generic default.
    final showOperation = d.description != null && d.operationLabel != defaultOperationLabel;
    final e = event;
    return ListTile(
      onTap: () => onTap(d),
      leading: CircleAvatar(radius: 8, backgroundColor: groupeColor(e?.newGroupe ?? d.groupe)),
      minLeadingWidth: 16,
      title: e == null
          ? Text(truncate(d.title, 90))
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '${eventText(e.newGroupe)} · ${formatDate(e.occurredAt)}${place == null ? '' : ' · $place'}',
                  style: Theme.of(context).textTheme.labelMedium?.copyWith(color: groupeColor(e.newGroupe)),
                ),
                Text(truncate(d.title, 90)),
              ],
            ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('${d.groupe.label} · ${d.distanceM.round()} m$depot'),
          if (showOperation) Padding(padding: const EdgeInsets.only(top: 4), child: _SmallChip(d.operationLabel)),
        ],
      ),
      trailing: flags.isEmpty
          ? null
          : Row(
              mainAxisSize: MainAxisSize.min,
              children: [for (final f in flags) Icon(flagIcon(f), size: 18, color: Theme.of(context).colorScheme.onSurfaceVariant)],
            ),
    );
  }
}

class _SmallChip extends StatelessWidget {
  const _SmallChip(this.label);

  final String label;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return DecoratedBox(
      decoration: BoxDecoration(color: scheme.secondaryContainer, borderRadius: BorderRadius.circular(4)),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
        child: Text(
          label,
          style: Theme.of(context).textTheme.labelSmall?.copyWith(color: scheme.onSecondaryContainer),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Details sheet

Future<void> showDossierDetails(BuildContext context, Dossier d) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    showDragHandle: true,
    builder: (context) => _DossierDetails(d),
  );
}

class _DossierDetails extends StatelessWidget {
  const _DossierDetails(this.d);

  final Dossier d;

  Future<void> _openOfficial(BuildContext context) async {
    final messenger = ScaffoldMessenger.of(context);
    final ok = await launchUrl(Uri.parse(d.lienSad!), mode: LaunchMode.externalApplication);
    if (!ok) messenger.showSnackBar(const SnackBar(content: Text('Impossible d\'ouvrir le lien.')));
  }

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    // The generic "Demande" label says nothing: the description becomes the title instead.
    final descriptionIsTitle = d.operationLabel == defaultOperationLabel && d.description != null;
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(descriptionIsTitle ? d.description! : d.operationLabel, style: text.titleLarge),
            const SizedBox(height: 4),
            SelectableText(d.idDossier, style: text.bodyMedium?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant)),
            if (d.description != null && !descriptionIsTitle) ...[
              const SizedBox(height: 12),
              Text(d.description!, style: text.bodyLarge),
            ],
            const SizedBox(height: 12),
            Row(
              children: [
                CircleAvatar(radius: 6, backgroundColor: groupeColor(d.groupe)),
                const SizedBox(width: 8),
                Expanded(child: Text(d.groupe.longLabel, style: text.titleSmall)),
              ],
            ),
            const SizedBox(height: 12),
            _field(context, 'Type de dossier', '${d.typeDossierLabel} (${d.typeDossier})'),
            if (d.operation != null) _field(context, 'Opération', d.operation!),
            if (d.dateDepot != null) _field(context, 'Déposé le', formatDate(d.dateDepot!)),
            _field(context, 'Mis à jour le', formatDate(d.dateMajSitg)),
            _field(context, 'Distance', '${d.distanceM.round()} m'),
            if (d.flags.isNotEmpty) ...[
              const SizedBox(height: 4),
              Wrap(
                spacing: 8,
                runSpacing: 4,
                children: [
                  for (final f in d.flags)
                    Chip(
                      avatar: Icon(flagIcon(f), size: 18),
                      label: Text(flagLabels[f] ?? f),
                      visualDensity: VisualDensity.compact,
                    ),
                ],
              ),
            ],
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                onPressed: d.lienSad == null ? null : () => _openOfficial(context),
                icon: const Icon(Icons.open_in_new),
                label: const Text('Voir le dossier officiel'),
              ),
            ),
            const SizedBox(height: 12),
            // Required by the SITG open-data terms (source attribution).
            Text(
              'Source : SITG – Système d\'information du territoire à Genève',
              style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant),
            ),
          ],
        ),
      ),
    );
  }

  Widget _field(BuildContext context, String label, String value) {
    final text = Theme.of(context).textTheme;
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(label, style: text.labelMedium?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant)),
          Text(value, style: text.bodyMedium),
        ],
      ),
    );
  }
}
