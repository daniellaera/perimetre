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
      await pumpDetails(tester, Dossier.fromJson(dossierJson({'parcelles': [parcel(7)]})));
      expect(find.text('Parcelle'), findsOneWidget);
      expect(find.text('Parcelle n° 7 · Onex'), findsOneWidget);
      expect(find.text("1'234 m²"), findsOneWidget);
      expect(find.text('EGRID CH000000007'), findsOneWidget);
      expect(find.textContaining('sur ou à côté'), findsNothing);
      expect(find.text('Site officiel de l\'État de Genève'), findsOneWidget);
    });

    testWidgets('two parcels: all listed with the caption', (tester) async {
      await pumpDetails(tester, Dossier.fromJson(dossierJson({'parcelles': [parcel(1), parcel(2)]})));
      expect(find.text('Parcelles'), findsOneWidget);
      expect(find.textContaining('sur ou à côté'), findsOneWidget);
      expect(find.text('Parcelle n° 1 · Onex'), findsOneWidget);
      expect(find.text('Parcelle n° 2 · Onex'), findsOneWidget);
      expect(find.text('Voir au registre foncier'), findsNWidgets(2));
    });

    testWidgets('the button opens lien_rf in the external browser', (tester) async {
      await pumpDetails(tester, Dossier.fromJson(dossierJson({'parcelles': [parcel(1, lien: 'https://ge.ch/rf?1')]})));
      await tester.ensureVisible(find.text('Voir au registre foncier'));
      await tester.tap(find.text('Voir au registre foncier'));
      await tester.pump();
      expect(launcher.launched, ['https://ge.ch/rf?1']);
      expect(launcher.options!.mode, PreferredLaunchMode.externalApplication);
    });

    testWidgets('without lien_rf it falls back to lien_registre_foncier, then to the constant', (tester) async {
      await pumpDetails(
        tester,
        Dossier.fromJson(dossierJson({'parcelles': [parcel(1, lien: null)], 'lien_registre_foncier': 'https://ge.ch/portail'})),
      );
      await tester.ensureVisible(find.text('Voir au registre foncier'));
      await tester.tap(find.text('Voir au registre foncier'));
      await tester.pump();
      expect(launcher.launched, ['https://ge.ch/portail']);

      Navigator.of(tester.element(find.byType(BottomSheet))).pop();
      await tester.pumpAndSettle();
      await pumpDetails(tester, Dossier.fromJson(dossierJson({'parcelles': [parcel(1, lien: null)]})));
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
      await pumpDetails(tester, Dossier.fromJson(dossierJson({'parcelles': [parcel(4)]})));
      await tester.ensureVisible(find.text('EGRID CH000000004'));
      await tester.tap(find.text('EGRID CH000000004'));
      await tester.pump();
      expect(copied, 'CH000000004');
      expect(find.text('EGRID copié.'), findsOneWidget);
    });

    testWidgets('a parcel without surface or EGRID still renders', (tester) async {
      await pumpDetails(tester, Dossier.fromJson(dossierJson({'parcelles': [{'numero': 9, 'commune': 'Vernier'}]})));
      expect(find.text('Parcelle n° 9 · Vernier'), findsOneWidget);
      expect(find.textContaining('EGRID'), findsNothing);
      expect(find.textContaining('m²'), findsNothing);
    });
  });
}
