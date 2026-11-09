/// A geocoded address suggestion from geo.admin.ch.
class AddressSuggestion {
  const AddressSuggestion({required this.label, required this.lat, required this.lon});

  /// Plain-text label, e.g. "Rue de Carouge 50 1205 Genève".
  final String label;
  final double lat;
  final double lon;

  factory AddressSuggestion.fromJson(Map<String, dynamic> attrs) => AddressSuggestion(
    label: (attrs['label'] as String).replaceAll(RegExp(r'<[^>]*>'), '').trim(),
    lat: (attrs['lat'] as num).toDouble(),
    lon: (attrs['lon'] as num).toDouble(),
  );
}

/// Status group computed by the backend from the SITG `statut` (see CLAUDE.md).
/// The backend never returns IGNORE or TERMINE from /dossiers; they map to [autre] elsewhere.
enum Groupe {
  depose('DEPOSE', 'Déposé', 'Déposé, en cours d\'examen'),
  autorise('AUTORISE', 'Autorisé', 'Autorisé'),
  chantier('CHANTIER', 'En chantier', 'En chantier'),
  recours('RECOURS', 'Recours', 'Recours en cours'),
  refuse('REFUSE', 'Refusé', 'Refusé, abandonné ou caduc'),
  autre(null, 'Autre', 'Autre statut');

  const Groupe(this.apiName, this.label, this.longLabel);

  /// Backend name; null for [autre].
  final String? apiName;
  final String label;
  final String longLabel;

  /// The groupes a subscription can be notified about, in display order.
  static const notifiable = [depose, autorise, chantier, recours, refuse];

  static Groupe fromApi(String? value) => values.firstWhere((g) => g.apiName == value, orElse: () => autre);
}

/// Event wording, identical to the push body's first sentence (backend `PushMessages`).
String eventText(Groupe groupe) => switch (groupe) {
  Groupe.depose => 'Nouvelle demande déposée — délai d\'opposition en cours',
  Groupe.autorise => 'Autorisation délivrée',
  Groupe.chantier => 'Les travaux commencent',
  Groupe.recours => 'Un recours a été déposé',
  Groupe.refuse => 'Le projet ne se fera pas',
  Groupe.autre => 'Changement de statut',
};

/// Flags computed by the backend from the description, in display order.
const flagLabels = {
  'arbres': 'Arbres (abattage, élagage)',
  'logements': 'Logements',
  'parking': 'Parking',
  'piscine': 'Piscine',
  'antenne': 'Antenne',
  'surelevation': 'Surélévation',
  'plq': 'PLQ',
};

/// SITG `type_dossier` → official name (`nom_dossier`).
const _typeDossierLabels = {
  'APA': 'Autorisation par procédure accélérée',
  'APAT': 'Autorisation par annonce de travaux',
  'DD': 'Demande définitive',
  'DP': 'Demande préalable',
  'DR': 'Demande de renseignements',
  'M': 'Démolition',
  'MPA': 'Démolition par procédure accélérée',
  'LER': 'Requête en approbation (loi sur les eaux et les routes)',
  'LER-PA': 'Requête en approbation (loi sur les eaux et les routes), procédure accélérée',
  'LER-PAT': 'Requête en approbation (loi sur les eaux et les routes), annonce de travaux',
};

/// SITG `type_operation` → short label. The full text comes from the API as `operation`.
const _typeOperationLabels = {
  'VIL': 'Nouvelle villa',
  'NOU': 'Construction nouvelle',
  'AGR': 'Agrandissement',
  'AGV': 'Agrandissement de villa',
  'REN': 'Rénovation',
  'PI': 'Petite construction',
  'EQU': 'Aménagement public',
  'AFF': 'Changement d\'affectation',
};

const defaultOperationLabel = 'Demande';

/// Trimmed SITG text with a capitalised first letter, or null when empty or "Non renseigné…".
String? _sitgText(String? raw) {
  final text = raw?.trim();
  if (text == null || text.isEmpty || text.toLowerCase().startsWith('non renseign')) return null;
  return text[0].toUpperCase() + text.substring(1);
}

/// Official portal of the State of Geneva for consulting the land registry; the fallback of [Parcelle.lienRf].
const registreFoncierUrl = 'https://www.ge.ch/consulter-registre-foncier';

/// A cadastral parcel a dossier is located on or next to (open data only: never an owner).
class Parcelle {
  const Parcelle({
    required this.numero,
    required this.commune,
    required this.surfaceM2,
    required this.egrid,
    required this.lienRf,
  });

  final int numero;
  final String commune;
  final int? surfaceM2;
  final String? egrid;

  /// SITG's official "extrait foncier" link for the parcel; null when absent.
  final String? lienRf;

  /// Null when the entry has no usable number or commune (it is then dropped by [Dossier.fromJson]).
  static Parcelle? tryParse(Object? json) {
    if (json is! Map) return null;
    final numero = json['numero'];
    final commune = json['commune'];
    if (numero is! num || commune is! String) return null;
    final surface = json['surface_m2'];
    final egrid = json['egrid'];
    final lien = json['lien_rf'];
    return Parcelle(
      numero: numero.toInt(),
      commune: commune,
      surfaceM2: surface is num ? surface.toInt() : null,
      egrid: egrid is String && egrid.isNotEmpty ? egrid : null,
      lienRf: lien is String && lien.isNotEmpty ? lien : null,
    );
  }
}

class Dossier {
  const Dossier({
    required this.idDossier,
    required this.typeDossier,
    required this.typeOperation,
    required this.operation,
    required this.description,
    required this.groupe,
    required this.dateDepot,
    required this.dateMajSitg,
    required this.flags,
    required this.lienSad,
    required this.lat,
    required this.lng,
    required this.distanceM,
    this.parcelles = const [],
    this.lienRegistreFoncier,
  });

  final String idDossier;
  final String typeDossier;
  final String? typeOperation;

  /// Cleaned SITG texts (see [_sitgText]); null when SITG has nothing useful.
  final String? operation;
  final String? description;
  final Groupe groupe;
  final DateTime? dateDepot;
  final DateTime dateMajSitg;
  final List<String> flags;
  final String? lienSad;
  final double lat;
  final double lng;
  final double distanceM;

  /// Parcels the dossier is on or next to (0 to 3); empty with an older backend or before its parcel import.
  final List<Parcelle> parcelles;

  /// The land registry portal, the fallback when a parcel has no `lien_rf`. Null with an older backend.
  final String? lienRegistreFoncier;

  factory Dossier.fromJson(Map<String, dynamic> json) => Dossier(
    idDossier: json['id_dossier'] as String,
    typeDossier: json['type_dossier'] as String,
    typeOperation: json['type_operation'] as String?,
    operation: _sitgText(json['operation'] as String?),
    description: _sitgText(json['description'] as String?),
    groupe: Groupe.fromApi(json['groupe'] as String?),
    dateDepot: json['date_depot'] == null ? null : DateTime.parse(json['date_depot'] as String),
    dateMajSitg: DateTime.parse(json['date_maj_sitg'] as String),
    flags: (json['flags'] as List).cast<String>(),
    lienSad: json['lien_sad'] as String?,
    lat: (json['lat'] as num).toDouble(),
    lng: (json['lng'] as num).toDouble(),
    distanceM: (json['distance_m'] as num).toDouble(),
    parcelles: [
      for (final p in json['parcelles'] is List ? json['parcelles'] as List : const [])
        ?Parcelle.tryParse(p),
    ],
    lienRegistreFoncier: json['lien_registre_foncier'] is String ? json['lien_registre_foncier'] as String : null,
  );

  bool get isDemolition => typeDossier == 'M' || typeDossier == 'MPA';

  /// Short human label: "Démolition" for M/MPA, else the type_operation label, else "Demande".
  String get operationLabel =>
      isDemolition ? 'Démolition' : (_typeOperationLabels[typeOperation] ?? defaultOperationLabel);

  /// Main text for the dossier: its description, else the operation label.
  String get title => description ?? operationLabel;

  String get typeDossierLabel => _typeDossierLabels[typeDossier] ?? typeDossier;
}

/// A subscription (perimeter) of this device, as returned by /devices/{id}/subscriptions.
class Subscription {
  const Subscription({
    required this.id,
    required this.lat,
    required this.lng,
    required this.radiusM,
    required this.categories,
    required this.label,
    required this.address,
  });

  final String id;
  final double lat;
  final double lng;
  final int radiusM;
  final List<Groupe> categories;

  /// The user's name for it (Domicile, Travail, free text), if any.
  final String? label;

  /// The address chosen in the app, e.g. "Rue de Carouge 50 1205 Genève".
  final String? address;

  /// Label, else the street part of the address.
  String get name => label ?? (address == null ? 'Périmètre' : streetAddress(address!));

  factory Subscription.fromJson(Map<String, dynamic> json) => Subscription(
    id: json['id'] as String,
    lat: (json['lat'] as num).toDouble(),
    lng: (json['lng'] as num).toDouble(),
    radiusM: json['radius_m'] as int,
    categories: (json['categories'] as List).map((c) => Groupe.fromApi(c as String)).toList(),
    label: json['label'] as String?,
    address: json['address'] as String?,
  );
}

/// "Rue de Carouge 50 1205 Genève" → "Rue de Carouge 50" (drops postcode and locality).
String streetAddress(String address) => address.replaceFirst(RegExp(r'\s+\d{4}\s+\S.*$'), '');

/// Body of POST and PUT /devices/{id}/subscriptions.
class SubscriptionInput {
  const SubscriptionInput({
    required this.lat,
    required this.lng,
    required this.radiusM,
    required this.categories,
    required this.label,
    required this.address,
  });

  final double lat;
  final double lng;
  final int radiusM;
  final List<Groupe> categories;
  final String? label;
  final String? address;

  Map<String, dynamic> toJson() => {
    'lat': lat,
    'lng': lng,
    'radius_m': radiusM,
    'categories': [for (final c in categories) c.apiName],
    'label': label,
    'address': address,
  };
}

/// This app installation on the backend: holds the push token and the subscriptions.
class Device {
  const Device({required this.id, required this.subscriptionCount, required this.maxSubscriptions});

  final String id;
  final int subscriptionCount;

  /// The backend's cap per device.
  final int maxSubscriptions;

  factory Device.fromJson(Map<String, dynamic> json) => Device(
    id: json['id'] as String,
    subscriptionCount: json['subscription_count'] as int,
    maxSubscriptions: json['max_subscriptions'] as int,
  );
}

/// An event in a subscription's perimeter: the dossier (with `distance_m` from the nearest matching subscription
/// point and its current groupe) plus what changed. [subscriptionIds]: the matching subscriptions, nearest first.
class DossierEvent {
  const DossierEvent({
    required this.eventId,
    required this.kind,
    required this.oldGroupe,
    required this.newGroupe,
    required this.occurredAt,
    required this.subscriptionIds,
    required this.dossier,
  });

  final int eventId;

  /// NEW or GROUPE_CHANGE.
  final String kind;
  final Groupe? oldGroupe;
  final Groupe newGroupe;
  final DateTime occurredAt;
  final List<String> subscriptionIds;
  final Dossier dossier;

  factory DossierEvent.fromJson(Map<String, dynamic> json) => DossierEvent(
    eventId: json['event_id'] as int,
    kind: json['kind'] as String,
    oldGroupe: json['old_groupe'] == null ? null : Groupe.fromApi(json['old_groupe'] as String),
    newGroupe: Groupe.fromApi(json['new_groupe'] as String),
    occurredAt: DateTime.parse(json['occurred_at'] as String).toLocal(),
    subscriptionIds: [for (final id in json['subscription_ids'] as List? ?? const []) id as String],
    dossier: Dossier.fromJson(json),
  );
}
