package ch.perimetre.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

import ch.perimetre.dossier.Groupe;
import ch.perimetre.event.EventKind;
import ch.perimetre.notification.PushPlanner.PlannedPush;

class PushPlannerTest {

    private static final UUID SUBSCRIPTION = UUID.randomUUID();

    private static List<PendingNotification> pending(int count) {
        return LongStream.rangeClosed(1, count)
                .mapToObj(i -> new PendingNotification(i, SUBSCRIPTION, "token", 100 + i, "APA " + i + "/1",
                        EventKind.GROUPE_CHANGE, Groupe.AUTORISE, "APA", "REN", "rénovation d'une façade", 120))
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
                .containsEntry("id_dossier", "APA 1/1")
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
    void neverMoreThanFourPushesPerSubscription() {
        for (int n = 0; n <= 50; n++) {
            assertThat(PushPlanner.plan(pending(n))).hasSizeLessThanOrEqualTo(4);
        }
    }
}
