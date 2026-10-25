package ch.perimetre.notification;

/** Sends one push. Implementations report failures in the result instead of throwing. */
public interface PushSender {

    PushResult send(String token, PushMessage message);
}
