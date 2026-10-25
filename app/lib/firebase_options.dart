// STUB. `flutterfire configure` overwrites this file with the generated DefaultFirebaseOptions; no other code
// change is needed. Until then, initPush() (lib/push/init_push.dart) catches the UnsupportedError below and
// runs the app with StubPushService (fake "dev-token-<uuid>" token, no real pushes).
import 'package:firebase_core/firebase_core.dart' show FirebaseOptions;

class DefaultFirebaseOptions {
  static FirebaseOptions get currentPlatform =>
      throw UnsupportedError('Firebase not configured: run `flutterfire configure` (see CLAUDE.md).');
}
