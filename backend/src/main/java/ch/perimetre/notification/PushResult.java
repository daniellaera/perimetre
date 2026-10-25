package ch.perimetre.notification;

/** {@code messageId}: the sender's id of a sent push (FCM: {@code projects/<id>/messages/<uuid>}), else null. */
public record PushResult(Status status, String messageId, String error) {

    public enum Status {
        SENT,
        /** The token is invalid or unregistered: counts towards deleting the subscription. */
        INVALID_TOKEN,
        /** Any other failure (network, quota, server error). */
        FAILED
    }

    static PushResult sent(String messageId) {
        return new PushResult(Status.SENT, messageId, null);
    }

    static PushResult invalidToken(String error) {
        return new PushResult(Status.INVALID_TOKEN, null, error);
    }

    static PushResult failed(String error) {
        return new PushResult(Status.FAILED, null, error);
    }
}
