# App Store submission (iOS)

State on 2026-10-30: the backend that the app needs is in production, and build **1.0.0 (3)**, the first
with the final icon (`app/tool/app_icon.svg`), is built but not uploaded. App Store Connect has not been
touched. Everything below is ready to paste. Steps marked **(Daniel)** need the Apple account.

## What is left before "Submit for Review" (Daniel)

1. **Upload and smoke test** the build through TestFlight (see "TestFlight" below) from a fresh install:
   perimeter → explanation page → iOS prompt → "Tester les notifications" → push received.
2. **Create the app record** (table below), then paste the listing, upload the screenshots and answer the
   age rating and App Privacy questions as written here.
3. Check that **contact@perimetre.ch** receives mail (support address on the site and in the privacy page).
4. Choose the build on the version page, add the review notes, and submit. Release: "Manually release this
   version", so the store page is checked before it goes live.

## App record

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

| Field | Value |
|---|---|
| Category | Primary **Utilitaires** (Utilities), secondary **Actualités** (News) |
| Content rights | "Yes, it contains, shows, or accesses third-party content, and I have the necessary rights": SITG open data (credited in the app and on the site, as its terms require) and the Mapbox / OpenStreetMap map (attribution shown on the map) |
| Privacy policy URL | https://perimetre.ch/privacy |
| Support URL | https://perimetre.ch |
| Marketing URL | https://perimetre.ch (optional) |
| Copyright | `2026 Daniel Laera` |
| Price | Free |
| Availability | Switzerland and France (the map covers the border communes). Other countries are possible, but the content is only about Geneva. |

## Store listing (French)

**Nom** (30 max): `Périmètre`

**Sous-titre** (30 max, 29 used):
```
Chantiers autour de chez vous
```

**Texte promotionnel** (170 max, 146 used; can be changed without a new version):
```
Soyez averti dès qu'une demande de construire est déposée, autorisée ou contestée près de chez vous, de votre travail ou de vos proches, à Genève.
```

**Mots-clés** (100 bytes max, 100 used; comma-separated, no spaces. The name and subtitle are already
indexed, so "chantier" and "Périmètre" are not repeated):
```
permis,construire,autorisation,Genève,SITG,travaux,voisinage,urbanisme,opposition,quartier,immeuble
```

**Description:**
```
Un immeuble va-t-il pousser en face de chez vous ? Des arbres vont-ils être abattus dans votre rue ? Périmètre vous prévient.

Choisissez une adresse dans le canton de Genève, un rayon de 100 à 1000 mètres et les étapes qui vous intéressent : Périmètre vous envoie une notification dès qu'une demande d'autorisation de construire y est déposée ou change de statut.

• Nouvelle demande déposée : vous le savez pendant le délai d'opposition, pas après.
• Autorisation délivrée, début des travaux, recours, refus : suivez chaque dossier jusqu'au bout.
• Plusieurs périmètres : domicile, travail, l'appartement de vos parents… jusqu'à 25, chacun avec son nom, son rayon et ses étapes.
• Explorez la carte : toutes les demandes en cours autour d'une adresse, avec leur description, leur statut et le lien vers le dossier officiel.
• Un fil « Récent » rassemble les derniers changements dans vos périmètres.

Sans compte et sans publicité : l'application ne vous demande ni nom, ni adresse e-mail, ni accès à votre position. Le serveur est hébergé en Suisse.

Les dossiers proviennent des données ouvertes du SITG (Système d'information du territoire à Genève), mises à jour chaque jour. Périmètre est un projet indépendant : il n'est pas édité par l'État de Genève.
```

**Notes pour la vérification (App Review)** (English):
```
No account or login. The app covers the Canton of Geneva (Switzerland) only.

To try it: tab "Mes périmètres" → type an address in Geneva (e.g. "Rue de Rive 15") → pick a suggestion → "Activer les notifications" → an explanation page, then the iOS permission prompt. "Tester les notifications" (below "Ajouter un périmètre") sends a test push to the device at once.

Real alerts follow the official building-permit data (SITG open data, Canton of Geneva), imported once a day, so one may not arrive during the review. The "Explorer" tab shows the current dossiers around any Geneva address, and a dossier opens its official page in Safari.
```

## Screenshots

6.9" iPhone, **1320 × 2868** PNG, light mode, no alpha channel, in `docs/app-store/`. Apple scales them down
for the smaller iPhones. The app is iPhone only, so no iPad set is needed. Upload them in this order:

| # | File | Shows |
|---|---|---|
| 1 | `1-explorer.png` | Explorer: the map around Rue de Rive 15, dossiers coloured by status, the list below |
| 2 | `2-perimetres.png` | Mes périmètres: "Domicile" and "Travail", then the "Récent" feed of alerts |
| 3 | `3-alerte.png` | An alert opened from the feed: description, status, dates, distance, official dossier link, SITG credit |
| 4 | `4-nouveau-perimetre.png` | Adding a perimeter: name, address, radius, the five statuses |
| 5 | `5-notifications.png` | The explanation shown before the iOS permission prompt |

The dossiers are real SITG data (imported 2026-10-30) and so are the alerts: the status changes SITG published
between 2026-10-24 and 2026-10-29. The addresses are public places in the old town, not anyone's home.

Regenerate them (after a UI change, not after an icon change, since the icon does not appear in them) on the
dedicated simulator **"Perimetre Store (iPhone 17 Pro Max)"**, which holds no production data, against the
local backend in the `dev` profile:

```bash
docker compose up -d && (cd backend && ./gradlew bootRun --args='--spring.profiles.active=dev') &
curl -X POST 'localhost:8090/admin/import?mode=full'      # fresh dossiers and real recent events
cd app && SIMULATOR_ID=<id> flutter drive --driver=test_driver/store_screenshots.dart \
  --target=integration_test/store_screenshots_test.dart --dart-define-from-file=config/dev.json -d <id>
```

The driver sets light mode and a 9:41 status bar, takes each picture with `xcrun simctl io` (status bar
included), and removes the alpha channel. The feed needs events near Rue de Rive: when the local database has
none, run a full import first (on a fresh database, run it twice: the first import only records a baseline).

## Age rating

App Store Connect → App Information → Age Rating. Answer **None** to every frequency question and **No** to
every yes/no question. The result is **4+**. For the questions that need some thought:

| Question | Answer | Why |
|---|---|---|
| Parental controls / age assurance | No | Neither exists in the app |
| Unrestricted web access | No | No general browser. The official dossier opens in Safari; the map attribution links (Mapbox, "Improve this map") open fixed pages |
| User-generated content | No | A perimeter's name is private to the device and never shown to anyone else |
| Messaging or chat | No | |
| Advertising | No | |
| Medical or wellness topics | None | |
| Violence, sexual content, nudity, profanity, horror, mature themes | None | Descriptions are SITG's administrative work descriptions |
| Alcohol, tobacco, drugs | None | |
| Gambling, simulated gambling, contests, loot boxes | No / None | |

## App Privacy ("nutrition label")

"Do you or your third-party partners collect data from this app?" → **Yes**. Declare exactly three types:

| Data type | Purpose | Linked to the user | Tracking |
|---|---|---|---|
| Location → **Precise Location** (the address and coordinates of each perimeter) | App Functionality | **Yes**¹ | No |
| User Content → **Other User Content** (the optional perimeter name, e.g. "Domicile") | App Functionality | **Yes**¹ | No |
| Identifiers → **Device ID** (the push token and the app's random installation id) | App Functionality | **Yes**¹ | No |

Everything else is **not collected**: contact info, health and fitness, financial info, sensitive info,
contacts, emails or messages, photos or videos, audio, gameplay, customer support, browsing history, search
history, user ID, purchases, product interaction, advertising data, other usage data, crash data, performance
data, other diagnostic data, coarse location, other data.

¹ There is no account, but the server stores the perimeters next to the push token, so the data is tied to
one installation. Apple counts that as linked. "Not linked" would need the data de-identified before it
reaches the server, and it is not.

This matches what the code does:
- Backend tables: `device` holds the push token, platform, failure counter and timestamps; `subscription`
  holds the point, radius, statuses, label, address and timestamps; `notification` holds the send history.
  Nothing else about the user is stored. Caddy keeps no access log.
- App: `firebase_core` and `firebase_messaging` only. There is no Analytics, Crashlytics or Performance SDK,
  no Mapbox SDK (plain tile requests) and no ads.
- `app/ios/Runner/PrivacyInfo.xcprivacy` declares the same three types (linked, not tracking, app
  functionality) and UserDefaults `CA92.1`. Keep the two in line, and in line with
  https://perimetre.ch/privacy.

Not declared as collection, by design, and disclosed on the privacy page instead:
- The address search sends the typed text to geo.admin.ch (Swiss federal geoportal). This is a plain API
  call, and we store nothing from it.
- Mapbox sees the map area being viewed when it serves tiles.
- FCM and APNs carry the push token and the notification text. This is covered by "Device ID".

The device location is never read, and the app has no location usage string.

## Facts about the build (verified with `flutter build ipa --release`)

- Bundle id `ch.perimetre.app`, version **1.0.0 (3)**, team `K433J5XKST`, iPhone only
  (`TARGETED_DEVICE_FAMILY = 1`), iOS 15+. Language `fr` (`CFBundleDevelopmentRegion`,
  `CFBundleLocalizations`), display name "Périmètre".
- Signed **Apple Distribution**; Release entitlements `aps-environment = production`
  (`Runner/RunnerRelease.entitlements`). The automatically managed store profile exists in the portal.
- `ITSAppUsesNonExemptEncryption = NO` (HTTPS only), so there is no export-compliance question per build.
- No usage strings are needed: no device location, and iOS localises the notification prompt itself.
- The release build calls `https://api.perimetre.ch` and needs the production Mapbox token
  (`--dart-define-from-file=config/prod.json`; the "Check Mapbox token" build phase fails without it).

## TestFlight (Daniel)

1. `cd app && flutter build ipa --release --dart-define-from-file=config/prod.json`. Every upload needs a
   higher build number (`version: 1.0.0+N` in `pubspec.yaml`).
2. Upload `app/build/ios/ipa/Périmètre.ipa` with **Transporter**, or with `xcrun altool --upload-app --type ios
   -f app/build/ios/ipa/*.ipa --apiKey <key id> --apiIssuer <issuer id>`, or from Xcode → Organizer.
3. Wait for processing (an e-mail, about 10–30 min).
4. Internal testing: add yourself and install with the TestFlight app. No review is needed.
5. Smoke test on a real iPhone, from a fresh install: create a perimeter → explanation page → permission
   prompt → device registered (`GET /devices/{id}` on prod) → "Tester les notifications" → push received.
