import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:perimetre/api/models.dart';
import 'package:perimetre/features/explore/widgets.dart';
import 'package:url_launcher_platform_interface/url_launcher_platform_interface.dart';

Map<String, dynamic> dossierJson([Map<String, dynamic> extra = const {}]) => {
  'id_dossier': 'APA 1/1',
  'type_dossier': 'APA',
  'type_operation': null,
  'operation': null,
  'description': 'Villa',
  'groupe': 'DEPOSE',
  'date_depot': '2026-10-01',
  'date_maj_sitg': '2026-10-02T00:00:00Z',
  'flags': <String>[],
  'lien_sad': 'https://example.test/sad',
  'lat': 46.2,
  'lng': 6.1,
  'distance_m': 10.0,
  ...extra,
};

Map<String, dynamic> parcel(int n, {Object? lien = 'https://ge.ch/x', int? surface = 1234}) => {
  'egrid': 'CH00000000$n',
  'commune': 'Onex',
  'numero': n,
  'surface_m2': surface,
  'lien_rf': lien,
};

/// A parcel as the 1.1 backend sends it, with context.
Map<String, dynamic> richParcel(int n, {List<dynamic>? batiments, List<dynamic>? restrictions, int? nb}) => {
  ...parcel(n),
  'type': 'DP communal',
  'plan_rf': '36',
  'lieu_dit': 'Les Eaux-Vives',
  'nb_batiments': nb ?? (batiments ?? const []).length,
  'batiments': batiments ?? const [],
  'restrictions': restrictions ?? const [],
};

Map<String, dynamic> batiment(String numero, {int? onParcel = 164, int? total = 165, int? year = 1990}) => {
  'egid': 1013526,
  'numero': numero,
  'destination': 'Hab plusieurs logements',
  'surface_m2': total,
  'surface_sur_parcelle_m2': onParcel,
  'annee_construction': year,
  'adresses': ['Rue du 31-Décembre 12'],
};

Map<String, dynamic> zone({String type = 'ZONE', int pct = 98}) => {
  'type': type,
  'code': '2',
  'nom': 'Zone 2',
  'description': 'Grandes maisons affectées à l\'habitat.',
  'statut': 'En vigueur',
  'surface_m2': 285,
  'part_pct': pct,
};

class _FakeLauncher extends UrlLauncherPlatform {
  final launched = <String>[];
  LaunchOptions? options;
  @override
  final linkDelegate = null;
  @override
  Future<bool> canLaunch(String url) async => true;
  @override
  Future<bool> launchUrl(String url, LaunchOptions o) async {
    launched.add(url);
    options = o;
    return true;
  }
}

Future<void> pumpDetails(WidgetTester tester, Dossier d) async {
  await tester.pumpWidget(
    MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(onPressed: () => showDossierDetails(context, d), child: const Text('open')),
        ),
      ),
    ),
  );
  await tester.tap(find.text('open'));
  await tester.pumpAndSettle();
}

void main() {
  group('parsing', () {
    test('an older backend without the fields gives no parcels and no crash', () {
      final d = Dossier.fromJson(dossierJson());
      expect(d.parcelles, isEmpty);
      expect(d.lienRegistreFoncier, isNull);
    });

    test('null or malformed fields are tolerated', () {
      expect(Dossier.fromJson(dossierJson({'parcelles': null, 'lien_registre_foncier': null})).parcelles, isEmpty);
      final d = Dossier.fromJson(
        dossierJson({
          'parcelles': [
            {'numero': 5, 'commune': 'Onex'},
            {'commune': 'sans numéro'},
            'junk',
            null,
          ],
        }),
      );
      expect(d.parcelles, hasLength(1));
      expect(d.parcelles.single.surfaceM2, isNull);
      expect(d.parcelles.single.egrid, isNull);
      expect(d.parcelles.single.lienRf, isNull);
    });

    test('the 1.1 context is parsed', () {
      final p = Dossier.fromJson(
        dossierJson({
          'parcelles': [
            richParcel(1, batiments: [batiment('A277')], restrictions: [zone()], nb: 3),
          ],
        }),
      ).parcelles.single;
      expect(p.type, 'DP communal');
      expect(p.planRf, '36');
      expect(p.lieuDit, 'Les Eaux-Vives');
      expect(p.nbBatiments, 3);
      expect(p.batiments.single.numero, 'A277');
      expect(p.batiments.single.surfaceSurParcelleM2, 164);
      expect(p.batiments.single.adresses, ['Rue du 31-Décembre 12']);
      expect(p.restrictions.single.partPct, 98);
      expect(p.restrictions.single.type, 'ZONE');
    });

    test('missing, null, empty and malformed context never crashes', () {
      // Older backend: only the five original fields.
      final old = Dossier.fromJson(
        dossierJson({
          'parcelles': [parcel(1)],
        }),
      ).parcelles.single;
      expect(old.type, isNull);
      expect(old.batiments, isEmpty);
      expect(old.restrictions, isEmpty);
      expect(old.nbBatiments, 0);

      final p = Dossier.fromJson(
        dossierJson({
          'parcelles': [
            {
              ...parcel(2),
              'type': '   ',
              'plan_rf': 36, // wrong type
              'lieu_dit': null,
              'nb_batiments': 'many',
              'batiments': [
                null,
                'junk',
                42,
                {'numero': 'B1', 'surface_m2': 'big', 'adresses': 'not a list'},
                {
                  'adresses': [null, 3, '', 'Rue A 1'],
                },
                <String, dynamic>{},
              ],
              'restrictions': [
                null,
                'junk',
                {'type': 'ZONE'}, // no name: dropped
                {'code': 'D3'}, // code stands in for the name
                {'nom': 'Zone 5', 'part_pct': '98', 'surface_m2': null},
              ],
            },
            {
              ...parcel(3),
              'batiments': 'oops',
              'restrictions': {'a': 1},
            },
            {...parcel(4), 'batiments': null, 'restrictions': null},
            {...parcel(5), 'batiments': <dynamic>[], 'restrictions': <dynamic>[]},
          ],
        }),
      ).parcelles;
      expect(p, hasLength(4));
      expect(p[0].type, isNull);
      expect(p[0].planRf, isNull);
      expect(p[0].lieuDit, isNull);
      // The malformed count falls back to what is there: B1, the address-only one and the empty object.
      expect(p[0].batiments, hasLength(3));
      expect(p[0].nbBatiments, 3);
      expect(p[0].batiments[0].surfaceM2, isNull);
      expect(p[0].batiments[0].adresses, isEmpty);
      expect(p[0].batiments[1].adresses, ['Rue A 1']);
      expect(p[0].restrictions.map((r) => r.nom), ['D3', 'Zone 5']);
      expect(p[0].restrictions.last.partPct, isNull);
      for (final other in p.skip(1)) {
        expect(other.batiments, isEmpty);
        expect(other.restrictions, isEmpty);
      }
    });

    test('full payload, also through an event', () {
      final json = dossierJson({
        'parcelles': [parcel(1), parcel(2)],
        'lien_registre_foncier': registreFoncierUrl,
        'event_id': 3,
        'kind': 'NEW',
        'old_groupe': null,
        'new_groupe': 'DEPOSE',
        'occurred_at': '2026-10-02T10:00:00Z',
        'subscription_ids': <String>[],
      });
      final e = DossierEvent.fromJson(json);
      expect(e.dossier.parcelles.map((p) => p.numero), [1, 2]);
      expect(e.dossier.parcelles.first.egrid, 'CH000000001');
      expect(e.dossier.lienRegistreFoncier, registreFoncierUrl);
    });
  });

  test('Swiss formatting', () {
    expect(formatSurface(1234), "1'234 m²");
    expect(formatSurface(330), '330 m²');
    expect(formatSurface(1234567), "1'234'567 m²");
    expect(formatSurface(null), isNull);
  });

  group('details sheet', () {
    late _FakeLauncher launcher;
    setUp(() {
      launcher = _FakeLauncher();
      UrlLauncherPlatform.instance = launcher;
    });

    testWidgets('no parcels: the section is absent', (tester) async {
      await pumpDetails(tester, Dossier.fromJson(dossierJson()));
      expect(find.textContaining('Parcelle'), findsNothing);
      expect(find.text('Voir au registre foncier'), findsNothing);
      expect(find.text('Voir le dossier officiel'), findsOneWidget);
    });

    testWidgets('one parcel: details, no multi-parcel caption', (tester) async {
      await pumpDetails(
        tester,
        Dossier.fromJson(
          dossierJson({
            'parcelles': [parcel(7)],
          }),
        ),
      );
      expect(find.text('Parcelle'), findsOneWidget);
      expect(find.text('Parcelle n° 7 · Onex'), findsOneWidget);
      expect(find.text("1'234 m²"), findsOneWidget);
      expect(find.text('EGRID CH000000007'), findsOneWidget);
      expect(find.textContaining('sur ou à côté'), findsNothing);
      expect(find.textContaining('Site officiel de l\'État de Genève'), findsOneWidget);
      expect(find.textContaining('propriétaire et extrait officiel'), findsOneWidget);
    });

    testWidgets('two parcels: all listed with the caption', (tester) async {
      await pumpDetails(
        tester,
        Dossier.fromJson(
          dossierJson({
            'parcelles': [parcel(1), parcel(2)],
          }),
        ),
      );
      expect(find.text('Parcelles'), findsOneWidget);
      expect(find.textContaining('sur ou à côté'), findsOneWidget);
      expect(find.text('Parcelle n° 1 · Onex'), findsOneWidget);
      expect(find.text('Parcelle n° 2 · Onex'), findsOneWidget);
      expect(find.text('Voir au registre foncier'), findsNWidgets(2));
    });

    testWidgets('the button opens lien_rf in the in-app browser', (tester) async {
      await pumpDetails(
        tester,
        Dossier.fromJson(
          dossierJson({
            'parcelles': [parcel(1, lien: 'https://ge.ch/rf?1')],
          }),
        ),
      );
      await tester.ensureVisible(find.text('Voir au registre foncier'));
      await tester.tap(find.text('Voir au registre foncier'));
      await tester.pump();
      expect(launcher.launched, ['https://ge.ch/rf?1']);
      // SFSafariViewController: the user stays in the app, the app never reads the page.
      expect(launcher.options!.mode, PreferredLaunchMode.inAppBrowserView);
    });

    testWidgets('without lien_rf it falls back to lien_registre_foncier, then to the constant', (tester) async {
      await pumpDetails(
        tester,
        Dossier.fromJson(
          dossierJson({
            'parcelles': [parcel(1, lien: null)],
            'lien_registre_foncier': 'https://ge.ch/portail',
          }),
        ),
      );
      await tester.ensureVisible(find.text('Voir au registre foncier'));
      await tester.tap(find.text('Voir au registre foncier'));
      await tester.pump();
      expect(launcher.launched, ['https://ge.ch/portail']);

      Navigator.of(tester.element(find.byType(BottomSheet))).pop();
      await tester.pumpAndSettle();
      await pumpDetails(
        tester,
        Dossier.fromJson(
          dossierJson({
            'parcelles': [parcel(1, lien: null)],
          }),
        ),
      );
      await tester.ensureVisible(find.text('Voir au registre foncier'));
      await tester.tap(find.text('Voir au registre foncier'));
      await tester.pump();
      expect(launcher.launched.last, registreFoncierUrl);
    });

    testWidgets('tapping the EGRID copies it', (tester) async {
      String? copied;
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'Clipboard.setData') copied = (call.arguments as Map)['text'] as String;
        return null;
      });
      addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
      await pumpDetails(
        tester,
        Dossier.fromJson(
          dossierJson({
            'parcelles': [parcel(4)],
          }),
        ),
      );
      await tester.ensureVisible(find.text('EGRID CH000000004'));
      await tester.tap(find.text('EGRID CH000000004'));
      await tester.pump();
      expect(copied, 'CH000000004');
      expect(find.text('EGRID copié.'), findsOneWidget);
    });

    testWidgets('a parcel without surface or EGRID still renders', (tester) async {
      await pumpDetails(
        tester,
        Dossier.fromJson(
          dossierJson({
            'parcelles': [
              {'numero': 9, 'commune': 'Vernier'},
            ],
          }),
        ),
      );
      expect(find.text('Parcelle n° 9 · Vernier'), findsOneWidget);
      expect(find.textContaining('EGRID'), findsNothing);
      expect(find.textContaining('m²'), findsNothing);
    });

    testWidgets('a bare parcel (older backend) shows no building or zone section', (tester) async {
      await pumpDetails(
        tester,
        Dossier.fromJson(
          dossierJson({
            'parcelles': [parcel(1)],
          }),
        ),
      );
      expect(find.textContaining('Bâtiment'), findsNothing);
      expect(find.textContaining('Zone'), findsNothing);
      expect(find.textContaining('Plan RF'), findsNothing);
      expect(find.byType(ExpansionTile), findsNothing);
    });

    testWidgets('type, plan and lieu-dit appear on the card', (tester) async {
      await pumpDetails(
        tester,
        Dossier.fromJson(
          dossierJson({
            'parcelles': [richParcel(7)],
          }),
        ),
      );
      expect(find.text("1'234 m² · Domaine public communal"), findsOneWidget);
      expect(find.text('Plan RF 36 · Lieu-dit Les Eaux-Vives'), findsOneWidget);
      // Empty lists: nothing collapsible at all.
      expect(find.byType(ExpansionTile), findsNothing);
    });

    testWidgets('buildings are collapsed, then show number, destination, surfaces and address', (tester) async {
      final d = Dossier.fromJson(
        dossierJson({
          'parcelles': [
            richParcel(7, batiments: [batiment('A277'), batiment('B3', onParcel: null, total: 80, year: null)]),
          ],
        }),
      );
      await pumpDetails(tester, d);
      expect(find.text('Bâtiments (2)'), findsOneWidget);
      expect(find.textContaining('A277'), findsNothing); // collapsed
      await tester.ensureVisible(find.text('Bâtiments (2)'));
      await tester.tap(find.text('Bâtiments (2)'));
      await tester.pumpAndSettle();
      expect(find.text('N° A277 · Hab plusieurs logements'), findsOneWidget);
      expect(find.text('Sur la parcelle : 164 m² (total 165 m²)'), findsOneWidget);
      expect(find.text('Construit en 1990'), findsOneWidget);
      expect(find.text('Rue du 31-Décembre 12'), findsNWidgets(2));
      // No footprint on the parcel: the total is shown, no year line for the second one.
      expect(find.text('Surface : 80 m²'), findsOneWidget);
      expect(find.text('Construit en 1990'), findsOneWidget);
    });

    testWidgets('a capped building list says how many are left out', (tester) async {
      final d = Dossier.fromJson(
        dossierJson({
          'parcelles': [
            richParcel(7, batiments: [batiment('A1')], nb: 6),
          ],
        }),
      );
      await pumpDetails(tester, d);
      await tester.ensureVisible(find.text('Bâtiments (6)'));
      await tester.tap(find.text('Bâtiments (6)'));
      await tester.pumpAndSettle();
      expect(find.text('et 5 autres bâtiments'), findsOneWidget);
    });

    testWidgets('zones are collapsed, then show name, kind, share and description', (tester) async {
      final d = Dossier.fromJson(
        dossierJson({
          'parcelles': [
            richParcel(
              7,
              restrictions: [
                zone(),
                zone(type: 'ZONE_DEVELOPPEMENT', pct: 100),
              ],
            ),
          ],
        }),
      );
      await pumpDetails(tester, d);
      expect(find.text('Zone et restrictions'), findsOneWidget);
      expect(find.text('Zone 2'), findsNothing); // collapsed
      await tester.ensureVisible(find.text('Zone et restrictions'));
      await tester.tap(find.text('Zone et restrictions'));
      await tester.pumpAndSettle();
      expect(find.text('Zone 2'), findsNWidgets(2));
      expect(find.text('Zone d\'affectation · 98\u00A0% de la parcelle'), findsOneWidget);
      expect(find.text('Zone de développement · 100\u00A0% de la parcelle'), findsOneWidget);
      expect(find.text('Grandes maisons affectées à l\'habitat.'), findsNWidgets(2));
    });

    testWidgets('two parcels, one with context and one bare, both render', (tester) async {
      final d = Dossier.fromJson(
        dossierJson({
          'parcelles': [
            richParcel(1, batiments: [batiment('A1')], restrictions: [zone()]),
            parcel(2),
          ],
        }),
      );
      await pumpDetails(tester, d);
      expect(find.text('Parcelles'), findsOneWidget);
      expect(find.byType(ExpansionTile), findsNWidgets(2)); // buildings + zones of parcel 1 only
      expect(find.text('Voir au registre foncier'), findsNWidgets(2));
      expect(find.text('Parcelle n° 2 · Onex'), findsOneWidget);
    });
  });

  test('labels', () {
    expect(parcelleTypeLabel('privé'), 'Privé');
    expect(parcelleTypeLabel('privé+dépendance'), 'Privé et dépendance');
    expect(parcelleTypeLabel('DP communal (hors indice)'), 'Domaine public communal');
    expect(parcelleTypeLabel('DP cantonal'), 'Domaine public cantonal');
    expect(parcelleTypeLabel('chemin vicinal'), 'Chemin vicinal');
    expect(parcelleTypeLabel('nouveau type'), 'Nouveau type');
    expect(restrictionTypeLabel('ZONE'), 'Zone d\'affectation');
    expect(restrictionTypeLabel('ZONE_DEVELOPPEMENT'), 'Zone de développement');
    expect(restrictionTypeLabel(null), 'Zone d\'affectation');
  });
}
