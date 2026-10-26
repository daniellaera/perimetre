package ch.perimetre.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import ch.perimetre.dossier.Groupe;
import ch.perimetre.event.EventKind;
import ch.perimetre.notification.PushPlanner.PlannedPush;

class PushPlannerTest {

    private static final UUID DEVICE = UUID.randomUUID();
    private static final UUID SUBSCRIPTION = UUID.randomUUID();

    private static PendingNotification notification(long id, UUID subscriptionId, String label, long eventId,
            double distanceM) {
        return new PendingNotification(id, DEVICE, subscriptionId, "token", label, null, 2, eventId,
                "APA " + eventId + "/1", EventKind.GROUPE_CHANGE, Groupe.AUTORISE, "APA", "REN",
                "rénovation d'une façade", distanceM);
    }

    private static List<PendingNotification> pending(int count) {
        return LongStream.rangeClosed(1, count).mapToObj(i -> notification(i, SUBSCRIPTION, null, 100 + i, 120))
                .toList();
    }

    @Test
    void nothingPendingPlansNothing() {
        assertThat(PushPlanner.plan(List.of())).isEmpty();
    }

    @Test
    void upToThreeNotificationsAreSentIndividually() {
        List<PlannedPush> plan = PushPlanner.plan(pending(3));

        assertThat(plan).hasSize(3);
        assertThat(plan).extracting(PlannedPush::notificationIds)
                .containsExactly(List.of(1L), List.of(2L), List.of(3L));
        assertThat(plan.getFirst().message().data())
                .containsEntry("id_dossier", "APA 101/1")
                .containsEntry("kind", "GROUPE_CHANGE")
                .containsEntry("new_groupe", "AUTORISE");
    }

    @Test
    void moreThanThreeNotificationsBecomeOneSummaryCoveringAll() {
        List<PlannedPush> plan = PushPlanner.plan(pending(4));

        assertThat(plan).hasSize(1);
        PlannedPush summary = plan.getFirst();
        assertThat(summary.notificationIds()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(summary.message().body()).isEqualTo("4 nouveaux changements autour de chez vous");
        assertThat(summary.message().data()).containsEntry("kind", "SUMMARY").containsEntry("count", "4");
    }

    @Test
    void eventMatchingSeveralSubscriptionsOfTheDeviceIsOnePush() {
        UUID travail = UUID.randomUUID();
        List<PlannedPush> plan = PushPlanner.plan(List.of(
                notification(1, travail, "Travail", 101, 300),
                notification(2, SUBSCRIPTION, "Domicile", 101, 50),
                notification(3, SUBSCRIPTION, "Domicile", 102, 70)));

        assertThat(plan).hasSize(2);
        assertThat(plan.getFirst().notificationIds()).containsExactly(2L, 1L);
        assertThat(plan.getFirst().message().title()).isEqualTo("Rénovation à 50 m · Domicile, Travail");
        assertThat(plan.get(1).notificationIds()).containsExactly(3L);
    }

    @Test
    void summaryThresholdCountsEventsNotMatches() {
        UUID travail = UUID.randomUUID();
        // 3 events, 6 notification rows: still individual pushes.
        List<PendingNotification> pending = LongStream.rangeClosed(1, 3)
                .boxed()
                .flatMap(e -> Stream.of(notification(e * 2, SUBSCRIPTION, "Domicile", e, 10),
                        notification(e * 2 + 1, travail, "Travail", e, 20)))
                .toList();

        assertThat(PushPlanner.plan(pending)).hasSize(3);
    }

    @Test
    void neverMoreThanFourPushesPerSubscription() {
        for (int n = 0; n <= 50; n++) {
            assertThat(PushPlanner.plan(pending(n))).hasSizeLessThanOrEqualTo(4);
        }
    }
}
