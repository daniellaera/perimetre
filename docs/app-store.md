# App Store / TestFlight checklist (iOS)

Prepared 2026-10-26. Nothing has been uploaded and App Store Connect has not been touched. The steps marked
**(Daniel)** need your Apple account.

## Ready in the repo (verified with a local `flutter build ipa --release`)

- Bundle id `ch.perimetre.app`, version **1.0.0 (1)** (`app/pubspec.yaml` `version: 1.0.0+1`), team `K433J5XKST`,
  iPhone only (`TARGETED_DEVICE_FAMILY = 1`, so no iPad screenshots are needed), iOS 15+.
- The archive is signed **Apple Distribution: Daniel Laera (K433J5XKST)** with entitlements `aps-environment =
  production`, `get-task-allow = false` and `beta-reports-active` (Release uses `Runner/RunnerRelease.entitlements`;
  Debug and Profile keep `Runner.entitlements` with `development`). The export made Xcode create the
  automatically managed profile "iOS Team Store Provisioning Profile: ch.perimetre.app" in the developer portal.
- `ITSAppUsesNonExemptEncryption = NO` (HTTPS only, exempt), so there is no export-compliance question per build.
- Language: `CFBundleDevelopmentRegion = fr`, `CFBundleLocalizations = [fr]`; display name "Périmètre".
- `Runner/PrivacyInfo.xcprivacy` (bundled): no tracking. Collected: precise location, other user content and
  device ID, all for app functionality and linked to the installation. Required-reason API: UserDefaults
  `CA92.1`. The plugins (Firebase, shared_preferences, …) bring their own manifests.
- Usage strings: none are needed. The app never asks for the device location (addresses are typed), and iOS
  has no usage-description key for notifications (the system prompt is localised by iOS). An unused
  `NSLocationWhenInUseUsageDescription` would only invite review questions, so it was not added.
- App icon: a complete set from a placeholder design (white perimeter + pin on the app red, opaque 1024 px;
  `app/tool/make_icon.swift`). The launch screen is the same mark on red. **Replace with a designed icon
  when you have one.**

## Must happen before a build goes to testers

1. **Backend deploy first.** The new app talks to `/devices` (multi-perimeter API, Flyway V6). Prod runs image
   `caea82fb`, which does not have it, so a build uploaded before the deploy fails on "Mes périmètres". After
   the validation nights: `pg_dump` (rollback past V6 = restore), then deploy `main`, then upload.
2. **Map tiles: Mapbox tokens.** Release builds load Mapbox tiles (`docs/map-tiles.md`) and need the production
   token: `app/config/prod.json` (copy of `config/example.json`), then `flutter build ipa --release
   --dart-define-from-file=config/prod.json`. Without it the build fails ("Check Mapbox token").
3. **Privacy page.** `deploy/site/privacy.html` was updated for multiple perimeters and the stored address
   (the live page wrongly says the address is not stored). It goes live with the next `ship.sh`.
4. **contact@perimetre.ch** is the support address on the site and in the privacy page. Check that the
   mailbox exists and receives mail (Infomaniak Mail on the domain).

## App Store Connect app record (Daniel)

My Apps → **+** → New App:

| Field | Value |
|---|---|
| Platform | iOS |
| Name | `Périmètre` (if taken: `Périmètre – Genève`) |
| Primary language | French |
| Bundle ID | `ch.perimetre.app` |
| SKU | `perimetre-ios` |
| User access | Full access |

App Information:
- Category: primary **Utilitaires**, secondary **Actualités**.
- Content rights: "Yes, it contains third-party content", meaning SITG open data used under its open data
  terms and credited in the app and on the site.
- Age rating: answer "None/No" to every question → **4+**.
- Pricing and availability: free; Switzerland and France (the border communes are in the map area).
- Privacy policy URL: **https://perimetre.ch/privacy**
- Support URL: **https://perimetre.ch** (contact address on the page). Marketing URL: optional, same.

## App Privacy ("nutrition label") answers

"Do you or your third-party partners collect data from this app?" → **Yes**.

| Data type | Collected | Use | Linked to the user | Tracking |
|---|---|---|---|---|
| Location → **Precise Location** (the address and coordinates of each perimeter) | Yes | App Functionality | **Yes**¹ | No |
| User Content → **Other User Content** (optional perimeter name, e.g. "Domicile") | Yes | App Functionality | **Yes**¹ | No |
| Identifiers → **Device ID** (push token + random installation id) | Yes | App Functionality | **Yes**¹ | No |
| Contact info, health, financial, contacts, browsing/search history, purchases, usage data, diagnostics, other | No | | | |

¹ There is no account, but Apple counts data tied to a device-level identifier as linked. Answering
"Not linked" would need the data de-identified before it reaches the server, and it is not: the push
token is stored next to the perimeters. This is the conservative and accurate answer, and it must match
`PrivacyInfo.xcprivacy`.

Judgement calls, recorded:
- The address search sends the typed text to geo.admin.ch (Swiss federal geoportal) as a plain API call.
  No SDK is involved and nothing is stored by us, so it is not declared as collection. Same for map tiles
  (Mapbox sees the displayed area; no Mapbox SDK, no telemetry) and FCM/APNs delivery (needed to send notifications, covered by
  "Device ID"). The privacy page discloses all three.
- No analytics and no crash reporting are linked into the app (firebase_core + firebase_messaging only).

## Store listing (French draft)

**Nom** (≤ 30): `Périmètre`

**Sous-titre** (≤ 30): `Chantiers autour de chez vous` (29)

**Texte promotionnel** (≤ 170):
> Soyez averti dès qu'une demande d'autorisation de construire est déposée, autorisée ou contestée près
> de chez vous, de votre travail ou de vos proches, à Genève.

**Description:**
> Un immeuble va-t-il pousser en face de chez vous ? Des arbres vont-ils être abattus dans votre rue ?
> Périmètre vous prévient.
>
> Choisissez une adresse à Genève, un rayon de 100 à 1000 mètres et les étapes qui vous intéressent :
> Périmètre vous envoie une notification dès qu'une demande d'autorisation de construire y est déposée
> ou change de statut.
>
> • Nouvelle demande déposée : vous le savez pendant le délai d'opposition, pas après.
> • Autorisation délivrée, début des travaux, recours, refus : suivez chaque dossier jusqu'au bout.
> • Plusieurs périmètres : domicile, travail, l'appartement de vos parents… autant que vous voulez, chacun
> avec son nom, son rayon et ses catégories.
> • Explorez la carte : toutes les demandes en cours autour d'une adresse, avec leur description, leur
> statut et le lien vers le dossier officiel.
>
> Sans compte, sans publicité : l'application n'a besoin ni de votre nom ni de votre adresse e-mail.
> Les données sont hébergées en Suisse.
>
> Les données des dossiers proviennent des données ouvertes du SITG (Système d'information du
> territoire à Genève). Périmètre est un projet indépendant, il n'est pas édité par l'État de Genève.

**Mots-clés** (≤ 100, comma-separated, no spaces):
`autorisation,construire,permis,chantier,Genève,SITG,travaux,voisinage,urbanisme,opposition,quartier` (99)

**Notes pour la vérification (App Review)** (English is fine):
> No account or login. To try it: Mes périmètres → type an address in Geneva (e.g. "Rue de Carouge 50") →
> pick a suggestion → Activer les notifications. Notifications follow the official building-permit
> data, which changes daily, so one may not arrive during the review. The Explorer tab shows current
> dossiers around any Geneva address. Data source: SITG open data (Canton of Geneva).

**Screenshots:** 6.9" iPhone (1320 × 2868) is required. Use a dedicated "iPhone 17 Pro Max" simulator
(never the one holding the prod subscription), against prod, with Explorer around a busy address and
Mes périmètres with two perimeters. `xcrun simctl io <id> screenshot file.png` gives the right size.

## TestFlight (Daniel)

1. `cd app && flutter build ipa --release --dart-define-from-file=config/prod.json`. The release build defaults to `https://api.perimetre.ch`. Each new
   upload needs a higher build number: bump `version: 1.0.0+N` in `pubspec.yaml`.
2. Upload `app/build/ios/ipa/Périmètre.ipa`, either with **Transporter** (drag and drop, signed in with your
   Apple ID) or with `xcrun altool --upload-app --type ios -f app/build/ios/ipa/*.ipa --apiKey <key id>
   --apiIssuer <issuer id>` (App Store Connect API key, `.p8` in `~/.appstoreconnect/private_keys/`).
   Xcode → Organizer → Distribute App → App Store Connect → Upload works too, from
   `app/build/ios/archive/Runner.xcarchive`.
3. Wait for processing (an e-mail, about 10–30 min). Encryption is answered by the plist.
4. **Internal testing:** add yourself (App Store Connect users, up to 100) → install with the TestFlight app. No
   review.
5. **External testing:** fill in Test Information (feedback e-mail, beta description, no sign-in). The
   first build goes through Beta App Review (about a day).
6. Smoke test on a real iPhone: permission prompt → device registered (`GET /devices/{id}` on prod) →
   `POST /admin/test-push?subscription_id=…` → push received → tap opens the dossier.
