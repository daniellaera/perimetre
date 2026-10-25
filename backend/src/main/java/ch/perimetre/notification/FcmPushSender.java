package ch.perimetre.notification;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.ApnsConfig;
import com.google.firebase.messaging.Aps;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;

/** Firebase Cloud Messaging (APNs on iOS goes through FCM too). */
final class FcmPushSender implements PushSender {

    private static final String APP_NAME = "perimetre";
    private static final Set<MessagingErrorCode> INVALID_TOKEN_CODES = Set.of(
            MessagingErrorCode.UNREGISTERED, MessagingErrorCode.INVALID_ARGUMENT, MessagingErrorCode.SENDER_ID_MISMATCH);

    private final FirebaseMessaging messaging;

    private FcmPushSender(FirebaseMessaging messaging) {
        this.messaging = messaging;
    }

    static FcmPushSender fromServiceAccount(Path serviceAccountJson) throws IOException {
        try (InputStream in = Files.newInputStream(serviceAccountJson)) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(in))
                    .build();
            // A refreshed Spring context (e.g. in tests) must not initialise the named app twice.
            FirebaseApp app = FirebaseApp.getApps().stream()
                    .filter(a -> a.getName().equals(APP_NAME))
                    .findFirst()
                    .orElseGet(() -> FirebaseApp.initializeApp(options, APP_NAME));
            return new FcmPushSender(FirebaseMessaging.getInstance(app));
        }
    }

    // The app sends a registration token (FirebaseMessaging.getToken()). firebase-admin 9.11 deprecates
    // setToken in favour of setFid (Firebase Installation ID); switching needs the app to send its FID.
    @SuppressWarnings("deprecation")
    @Override
    public PushResult send(String token, PushMessage message) {
        Message fcm = Message.builder()
                .setToken(token)
                .setNotification(Notification.builder().setTitle(message.title()).setBody(message.body()).build())
                .putAllData(message.data())
                .setApnsConfig(ApnsConfig.builder().setAps(Aps.builder().setSound("default").build()).build())
                .build();
        try {
            messaging.send(fcm);
            return PushResult.sent();
        } catch (FirebaseMessagingException e) {
            String error = e.getMessagingErrorCode() + ": " + e.getMessage();
            return INVALID_TOKEN_CODES.contains(e.getMessagingErrorCode())
                    ? PushResult.invalidToken(error)
                    : PushResult.failed(error);
        }
    }
}
