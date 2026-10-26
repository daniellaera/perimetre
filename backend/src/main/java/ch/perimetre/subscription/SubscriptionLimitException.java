package ch.perimetre.subscription;

/** A device already has the maximum number of subscriptions. */
class SubscriptionLimitException extends RuntimeException {

    static final String CODE = "SUBSCRIPTION_LIMIT_REACHED";

    private final int max;

    SubscriptionLimitException(int max) {
        super("Vous avez atteint le maximum de " + max + " périmètres sur cet appareil. "
                + "Supprimez-en un pour en ajouter un autre.");
        this.max = max;
    }

    int max() {
        return max;
    }
}
