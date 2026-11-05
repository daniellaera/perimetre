package ch.perimetre.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ch.perimetre.dossier.Groupe;
import ch.perimetre.event.EventKind;

class PushMessagesTest {

    private static final UUID DEVICE = UUID.randomUUID();
    private static final UUID SUBSCRIPTION = UUID.fromString("00000000-0000-4000-8000-000000000001");

    /** A single unlabelled subscription at "Rue du Rhône 1 1204 Genève". */
    private static PendingNotification notification(String description, Groupe newGroupe, double distanceM) {
        return new PendingNotification(1, DEVICE, SUBSCRIPTION, "token", null, "Rue du Rhône 1 1204 Genève", 1, 7,
                "DD 123456/1", newGroupe == Groupe.DEPOSE ? EventKind.NEW : EventKind.GROUPE_CHANGE, newGroupe,
                "DD", "VIL", description, distanceM);
    }

    /** Event {@code eventId} (AUTORISE) matched by a subscription of a device holding {@code deviceSubscriptions}. */
    private static PendingNotification match(long eventId, UUID subscriptionId, String label, String address,
            int deviceSubscriptions, double distanceM, String description) {
        return new PendingNotification(eventId * 10, DEVICE, subscriptionId, "token", label, address,
                deviceSubscriptions, eventId, "DD " + eventId + "/1", EventKind.GROUPE_CHANGE, Groupe.AUTORISE, "DD",
                "VIL", description, distanceM);
    }

    private static PendingNotification match(long eventId, UUID subscriptionId, String label, String address,
            int deviceSubscriptions, double distanceM) {
        return match(eventId, subscriptionId, label, address, deviceSubscriptions, distanceM, "construction d'une villa");
    }

    @Test
    void titleIsDistanceAndLocalityBodyIsChangeLabelAndDescription() {
        PushMessage m = PushMessages.forEvent(
                match(7, SUBSCRIPTION, "Peillonnex", null, 1, 103.6, "modification d'une installation"));

        assertThat(m.title()).isEqualTo("104 m · Peillonnex");
        assertThat(m.body()).isEqualTo("Autorisation délivrée — Modification d'une installation");
        assertThat(m.data()).isEqualTo(Map.of("id_dossier", "DD 7/1", "kind", "GROUPE_CHANGE",
                "new_groupe", "AUTORISE", "subscription_id", SUBSCRIPTION.toString()));
    }

    @ParameterizedTest
    @CsvSource({"0, 0 m", "12.4, 12 m", "149.6, 150 m", "999.4, 999 m", "999.6, '1,0 km'", "1000, '1,0 km'",
            "1234, '1,2 km'", "1250, '1,3 km'", "1960, '2,0 km'"})
    void distanceIsMetresUnderAKilometreThenKilometresWithAComma(double metres, String expected) {
        assertThat(PushMessages.distance(metres)).isEqualTo(expected);
    }

    @Test
    void everyChangeLabelStartsTheBody() {
        assertThat(PushMessages.body(Groupe.DEPOSE, "pose d'une pergola")).isEqualTo("Nouvelle demande · opposition possible — Pose d'une pergola");
        assertThat(PushMessages.body(Groupe.CHANTIER, "x")).isEqualTo("Les travaux commencent — X");
        assertThat(PushMessages.body(Groupe.RECOURS, "x")).isEqualTo("Un recours a été déposé — X");
        assertThat(PushMessages.body(Groupe.REFUSE, "x")).isEqualTo("Le projet ne se fera pas — X");
    }

    @Test
    void bodyDescriptionIsCappedAt90Chars() {
        PushMessage m = PushMessages.forEvent(notification("a".repeat(120), Groupe.RECOURS, 300));

        assertThat(m.body()).isEqualTo("Un recours a été déposé — A" + "a".repeat(89) + "…");
    }

    @Test
    void missingDescriptionLeavesTheChangeLabelAlone() {
        assertThat(PushMessages.forEvent(notification("   ", Groupe.REFUSE, 455.2)).body())
                .isEqualTo("Le projet ne se fera pas");
        assertThat(PushMessages.forEvent(notification(null, Groupe.DEPOSE, 5)).body()).isEqualTo("Nouvelle demande · opposition possible");
    }

    @Test
    void missingLocalityFallsBackToTheStreetAddress() {
        PushMessage m = PushMessages.forEvent(notification("pose d'une pergola", Groupe.CHANTIER, 87));

        assertThat(m.title()).isEqualTo("87 m de Rue du Rhône 1");
    }

    @Test
    void missingLocalityAndAddressLeavesTheDistance() {
        PushMessage m = PushMessages.forEvent(match(7, SUBSCRIPTION, null, "  ", 1, 80, "x"));

        assertThat(m.title()).isEqualTo("80 m");
    }

    @Test
    void longLocalityAndAddressAreTruncated() {
        assertThat(PushMessages.forEvent(match(7, SUBSCRIPTION, "L".repeat(50), null, 1, 80, "x")).title())
                .isEqualTo("80 m · " + "L".repeat(30) + "…");
        assertThat(PushMessages.forEvent(match(7, SUBSCRIPTION, null, "A".repeat(50) + " 1204 Genève", 1, 80, "x"))
                .title()).isEqualTo("80 m de " + "A".repeat(30) + "…");
    }

    @Test
    void testPushUsesTheRealFormatOnASampleAndNamesNoDossier() {
        PushMessage m = PushMessages.test(match(0, SUBSCRIPTION, "Peillonnex", null, 2, 104, "x"));

        assertThat(m.title()).isEqualTo("104 m · Peillonnex");
        assertThat(m.body()).isEqualTo("Nouvelle demande · opposition possible — Modification d'une installation de télécommunication "
                + "mobile existante");
        assertThat(m.data()).isEqualTo(Map.of("kind", "TEST"));
    }

    @Test
    void testPushWithoutAnyPerimeterShowsJustTheDistance() {
        assertThat(PushMessages.test(null).title()).isEqualTo("104 m");
    }

    @Test
    void singleUnlabelledSubscriptionIsNamedByItsStreetAddress() {
        PushMessage m = PushMessages.forEvent(match(7, SUBSCRIPTION, null, "Rue du Rhône 1 1204 Genève", 1, 80, "x"));

        assertThat(m.title()).isEqualTo("80 m de Rue du Rhône 1");
    }

    @Test
    void unlabelledSubscriptionOfASeveralSubscriptionDeviceIsNamedByItsStreetAddress() {
        PushMessage m = PushMessages.forEvent(match(7, SUBSCRIPTION, null, "Rue du Rhône 1 1204 Genève", 2, 80, "x"));

        assertThat(m.title()).isEqualTo("80 m · Rue du Rhône 1");
    }

    @Test
    void oneEventMatchingSeveralSubscriptionsNamesThemAllNearestFirst() {
        UUID travail = UUID.randomUUID();
        PushMessage m = PushMessages.forEvent(List.of(
                match(7, SUBSCRIPTION, "Domicile", null, 3, 80, "x"),
                match(7, travail, "Travail", null, 3, 240, "x"),
                match(7, UUID.randomUUID(), "Domicile", null, 3, 300, "x")));

        assertThat(m.title()).isEqualTo("80 m · Domicile, Travail");
        assertThat(m.data()).containsEntry("subscription_id", SUBSCRIPTION.toString());
    }

    @Test
    void summaryCountsEventsPerNamedPlace() {
        UUID travail = UUID.randomUUID();
        PushMessage m = PushMessages.summary(List.of(
                List.of(match(1, SUBSCRIPTION, "Domicile", null, 2, 80), match(1, travail, "Travail", null, 2, 90)),
                List.of(match(2, SUBSCRIPTION, "Domicile", null, 2, 80)),
                List.of(match(3, SUBSCRIPTION, "Domicile", null, 2, 80)),
                List.of(match(4, travail, "Travail", null, 2, 80))));

        assertThat(m.title()).isEqualTo("Périmètre");
        assertThat(m.body()).isEqualTo("4 nouveaux changements (Domicile : 3, Travail : 2)");
        assertThat(m.data()).containsEntry("kind", "SUMMARY").containsEntry("count", "4");
    }

    @Test
    void summaryWithoutNamedPlacesKeepsTheOriginalWording() {
        PushMessage m = PushMessages.summary(List.of(
                List.of(match(1, SUBSCRIPTION, null, "Rue du Rhône 1 1204 Genève", 1, 80)),
                List.of(match(2, SUBSCRIPTION, null, "Rue du Rhône 1 1204 Genève", 1, 80))));

        assertThat(m.body()).isEqualTo("2 nouveaux changements autour de chez vous");
    }
}
