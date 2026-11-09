import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../api/models.dart';

/// Swiss thousands separator: 1234 -> "1'234".
String formatSwissInt(int n) {
  final digits = n.abs().toString();
  final out = StringBuffer(n < 0 ? '-' : '');
  for (var i = 0; i < digits.length; i++) {
    if (i > 0 && (digits.length - i) % 3 == 0) out.write("'");
    out.write(digits[i]);
  }
  return out.toString();
}

/// "1'234 m²", or null when the surface is unknown.
String? formatSurface(int? m2) => m2 == null ? null : '${formatSwissInt(m2)} m²';

/// The parcel category as SITG publishes it, in plain French. Unknown values are shown capitalised as they come.
String parcelleTypeLabel(String raw) {
  switch (raw.trim().toLowerCase()) {
    case 'privé':
      return 'Privé';
    case 'privé+dépendance':
      return 'Privé et dépendance';
    case 'dépendance':
      return 'Dépendance';
    case 'dp communal':
    case 'dp communal (hors indice)':
      return 'Domaine public communal';
    case 'dp cantonal':
      return 'Domaine public cantonal';
    case 'chemin vicinal':
      return 'Chemin vicinal';
    case 'droits spéciaux':
      return 'Droits spéciaux';
  }
  final t = raw.trim();
  return t[0].toUpperCase() + t.substring(1);
}

String restrictionTypeLabel(String? type) =>
    type == 'ZONE_DEVELOPPEMENT' ? 'Zone de développement' : 'Zone d\'affectation';

/// "98 %" (Swiss/French typography: a no-break space before the sign).
String formatPercent(int pct) => '$pct %';

/// The "Parcelle(s)" block of the details sheet: one card per parcel (number, commune, surface, category, EGRID
/// that a tap or long-press copies), then collapsible "Bâtiments" and "Zone et restrictions" only when there is
/// something to show, and a button to the State of Geneva's land registry website for what is not open data
/// (owner, official extract). Open data only; lives on the details sheet, never in lists or feeds.
class ParcellesSection extends StatelessWidget {
  const ParcellesSection(this.parcelles, {super.key, this.fallbackUrl});

  final List<Parcelle> parcelles;

  /// Used for a parcel without `lien_rf`; the portal's constant when the backend does not send it.
  final String? fallbackUrl;

  @override
  Widget build(BuildContext context) {
    if (parcelles.isEmpty) return const SizedBox.shrink();
    final text = Theme.of(context).textTheme;
    final muted = text.bodySmall?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(height: 8),
        const Divider(),
        Text(parcelles.length > 1 ? 'Parcelles' : 'Parcelle', style: text.titleSmall),
        if (parcelles.length > 1) ...[
          const SizedBox(height: 2),
          Text('Le dossier se situe sur ou à côté de ces parcelles.', style: muted),
        ],
        for (final p in parcelles) ParcelleCard(p, fallbackUrl: fallbackUrl ?? registreFoncierUrl),
      ],
    );
  }
}

/// Opens [url] in the in-app browser (SFSafariViewController on iOS): the user stays in the app and the app never
/// reads or renders the page. Returns whether it could be opened.
Future<bool> openInAppBrowser(String url) => launchUrl(Uri.parse(url), mode: LaunchMode.inAppBrowserView);

class ParcelleCard extends StatelessWidget {
  const ParcelleCard(this.p, {super.key, required this.fallbackUrl});

  final Parcelle p;
  final String fallbackUrl;

  Future<void> _copyEgrid(BuildContext context) async {
    final messenger = ScaffoldMessenger.of(context);
    await Clipboard.setData(ClipboardData(text: p.egrid!));
    messenger.showSnackBar(const SnackBar(content: Text('EGRID copié.'), persist: false));
  }

  Future<void> _open(BuildContext context) async {
    final messenger = ScaffoldMessenger.of(context);
    final ok = await openInAppBrowser(p.lienRf ?? fallbackUrl);
    if (!ok) messenger.showSnackBar(const SnackBar(content: Text('Impossible d\'ouvrir le lien.'), persist: false));
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    final text = Theme.of(context).textTheme;
    final muted = text.bodyMedium?.copyWith(color: scheme.onSurfaceVariant);
    final small = text.bodySmall?.copyWith(color: scheme.onSurfaceVariant);
    final facts = [?formatSurface(p.surfaceM2), if (p.type != null) parcelleTypeLabel(p.type!)];
    final where = [if (p.planRf != null) 'Plan RF ${p.planRf}', if (p.lieuDit != null) 'Lieu-dit ${p.lieuDit}'];
    final nbBatiments = math.max(p.nbBatiments, p.batiments.length);
    return Card(
      margin: const EdgeInsets.only(top: 10),
      elevation: 0,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: BorderSide(color: scheme.outlineVariant),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(14, 12, 14, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Parcelle n° ${p.numero} · ${p.commune}', style: text.titleMedium),
            if (facts.isNotEmpty) Text(facts.join(' · '), style: muted),
            if (where.isNotEmpty) Text(where.join(' · '), style: small),
            if (p.egrid != null)
              InkWell(
                onTap: () => _copyEgrid(context),
                onLongPress: () => _copyEgrid(context),
                child: Padding(
                  padding: const EdgeInsets.symmetric(vertical: 6),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Flexible(child: Text('EGRID ${p.egrid}', style: small)),
                      const SizedBox(width: 6),
                      Icon(Icons.copy, size: 14, color: scheme.onSurfaceVariant),
                    ],
                  ),
                ),
              ),
            if (p.batiments.isNotEmpty)
              _Collapsible(
                title: nbBatiments > 1 ? 'Bâtiments ($nbBatiments)' : 'Bâtiment',
                icon: Icons.apartment,
                children: [
                  for (final b in p.batiments) BatimentTile(b),
                  if (nbBatiments > p.batiments.length)
                    Padding(
                      padding: const EdgeInsets.only(top: 4),
                      child: Text(
                        'et ${nbBatiments - p.batiments.length} autre${nbBatiments - p.batiments.length > 1 ? 's' : ''} '
                        'bâtiment${nbBatiments - p.batiments.length > 1 ? 's' : ''}',
                        style: small,
                      ),
                    ),
                ],
              ),
            if (p.restrictions.isNotEmpty)
              _Collapsible(
                title: 'Zone et restrictions',
                icon: Icons.layers_outlined,
                children: [for (final r in p.restrictions) RestrictionTile(r)],
              ),
            const Divider(height: 20),
            // What is not open data (owner, official extract) is on the State's website, opened in the app.
            TextButton.icon(
              onPressed: () => _open(context),
              style: TextButton.styleFrom(padding: EdgeInsets.zero, alignment: Alignment.centerLeft),
              icon: const Icon(Icons.account_balance_outlined, size: 18),
              label: const Text('Voir au registre foncier'),
            ),
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Text('Site officiel de l\'État de Genève : propriétaire et extrait officiel.', style: small),
            ),
          ],
        ),
      ),
    );
  }
}

/// A section that is collapsed until tapped.
class _Collapsible extends StatelessWidget {
  const _Collapsible({required this.title, required this.icon, required this.children});

  final String title;
  final IconData icon;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Theme(
      // No divider lines above and below the tile.
      data: Theme.of(context).copyWith(dividerColor: Colors.transparent),
      child: ExpansionTile(
        tilePadding: EdgeInsets.zero,
        childrenPadding: const EdgeInsets.only(bottom: 8),
        dense: true,
        shape: const Border(),
        collapsedShape: const Border(),
        expandedCrossAxisAlignment: CrossAxisAlignment.start,
        expandedAlignment: Alignment.centerLeft,
        leading: Icon(icon, size: 20),
        title: Text(title, style: Theme.of(context).textTheme.titleSmall),
        children: children,
      ),
    );
  }
}

class BatimentTile extends StatelessWidget {
  const BatimentTile(this.b, {super.key});

  final Batiment b;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final small = text.bodySmall?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant);
    final head = [if (b.numero != null) 'N° ${b.numero}', ?b.destination];
    final onParcel = formatSurface(b.surfaceSurParcelleM2);
    final total = formatSurface(b.surfaceM2);
    final surface = onParcel != null
        ? 'Sur la parcelle : $onParcel${total != null && total != onParcel ? ' (total $total)' : ''}'
        : total != null
        ? 'Surface : $total'
        : null;
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(head.isEmpty ? 'Bâtiment' : head.join(' · '), style: text.bodyMedium),
          if (surface != null) Text(surface, style: small),
          if (b.anneeConstruction != null) Text('Construit en ${b.anneeConstruction}', style: small),
          for (final a in b.adresses) Text(a, style: small),
        ],
      ),
    );
  }
}

class RestrictionTile extends StatelessWidget {
  const RestrictionTile(this.r, {super.key});

  final Restriction r;

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    final small = text.bodySmall?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant);
    final facts = [restrictionTypeLabel(r.type), if (r.partPct != null) '${formatPercent(r.partPct!)} de la parcelle'];
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(r.nom, style: text.bodyMedium),
          Text(facts.join(' · '), style: small),
          if (r.description != null)
            Padding(
              padding: const EdgeInsets.only(top: 2),
              child: Text(r.description!, style: small),
            ),
        ],
      ),
    );
  }
}
