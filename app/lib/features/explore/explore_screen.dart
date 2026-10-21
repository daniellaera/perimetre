import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../api/models.dart';
import 'providers.dart';
import 'widgets.dart';

/// The single screen: address + radius on top, map in the middle, dossier list in a sheet.
class ExploreScreen extends ConsumerWidget {
  const ExploreScreen({super.key});

  static const _sheetInitialSize = 0.35;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final address = ref.watch(selectedAddressProvider);
    final radius = ref.watch(radiusMetresProvider);
    final async = ref.watch(dossiersProvider);

    final status = address == null
        ? ListStatus.idle
        : async.isLoading
        ? ListStatus.loading
        : async.hasError
        ? ListStatus.error
        : ListStatus.data;
    final dossiers = status == ListStatus.error ? const <Dossier>[] : (async.value ?? const <Dossier>[]);

    void openDetails(Dossier d) => showDossierDetails(context, d);

    return Scaffold(
      resizeToAvoidBottomInset: false,
      body: SafeArea(
        bottom: false,
        child: Column(
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(16, 8, 16, 0),
              child: Column(children: [AddressField(), RadiusSlider()]),
            ),
            Expanded(
              child: Stack(
                children: [
                  DossierMap(
                    center: address,
                    radius: radius,
                    dossiers: dossiers,
                    bottomInsetFraction: _sheetInitialSize,
                    onTapDossier: openDetails,
                  ),
                  DossierSheet(
                    initialSize: _sheetInitialSize,
                    status: status,
                    radius: radius,
                    dossiers: dossiers,
                    onRetry: () => ref.invalidate(dossiersProvider),
                    onTapDossier: openDetails,
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
