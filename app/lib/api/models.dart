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
/// The backend never returns IGNORE or TERMINE from /dossiers.
enum Groupe {
  depose('Déposé', 'Déposé, en cours d\'examen'),
  autorise('Autorisé', 'Autorisé'),
  chantier('En chantier', 'En chantier'),
  recours('Recours', 'Recours en cours'),
  refuse('Refusé', 'Refusé, abandonné ou caduc'),
  autre('Autre', 'Autre statut');

  const Groupe(this.label, this.longLabel);

  final String label;
  final String longLabel;

  static Groupe fromApi(String? value) => switch (value) {
    'DEPOSE' => depose,
    'AUTORISE' => autorise,
    'CHANTIER' => chantier,
    'RECOURS' => recours,
    'REFUSE' => refuse,
    _ => autre,
  };
}

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
  );

  bool get isDemolition => typeDossier == 'M' || typeDossier == 'MPA';

  /// Short human label: "Démolition" for M/MPA, else the type_operation label, else "Demande".
  String get operationLabel =>
      isDemolition ? 'Démolition' : (_typeOperationLabels[typeOperation] ?? defaultOperationLabel);

  /// Main text for the dossier: its description, else the operation label.
  String get title => description ?? operationLabel;

  String get typeDossierLabel => _typeDossierLabels[typeDossier] ?? typeDossier;
}
