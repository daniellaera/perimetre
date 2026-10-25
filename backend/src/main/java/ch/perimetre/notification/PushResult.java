package ch.perimetre.notification;

public record PushResult(Status status, String error) {

    public enum Status {
        SENT,
        /** The token is invalid or unregistered: counts towards deleting the subscription. */
        INVALID_TOKEN,
        /** Any other failure (network, quota, server error). */
        FAILED
    }

    static PushResult sent() {
        return new PushResult(Status.SENT, null);
    }

    static PushResult invalidToken(String error) {
        return new PushResult(Status.INVALID_TOKEN, error);
    }

    static PushResult failed(String error) {
        return new PushResult(Status.FAILED, error);
    }
}
