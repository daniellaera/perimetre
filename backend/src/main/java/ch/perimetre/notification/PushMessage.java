package ch.perimetre.notification;

import java.util.Map;

/** A platform-neutral push: visible title/body plus string data for the app. */
public record PushMessage(String title, String body, Map<String, String> data) {
}
