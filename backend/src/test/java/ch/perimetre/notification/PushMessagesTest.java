package ch.perimetre.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import ch.perimetre.dossier.Groupe;
import ch.perimetre.event.EventKind;

class PushMessagesTest {

    private static PendingNotification notification(String typeDossier, String typeOperation, String description,
            Groupe newGroupe, double distanceM) {
        return new PendingNotification(1, UUID.randomUUID(), "token", 7, "DD 123456/1",
                newGroupe == Groupe.DEPOSE ? EventKind.NEW : EventKind.GROUPE_CHANGE, newGroupe,
                typeDossier, typeOperation, description, distanceM);
    }

    @Test
    void titleUsesOperationLabelAndRoundedDistance() {
        PushMessage m = PushMessages.forEvent(
                notification("DD", "VIL", "construction d'une villa", Groupe.AUTORISE, 149.6));

        assertThat(m.title()).isEqualTo("Nouvelle villa à 150 m");
        assertThat(m.body()).isEqualTo("Autorisation délivrée. Construction d'une villa");
        assertThat(m.data()).isEqualTo(Map.of("id_dossier", "DD 123456/1", "kind", "GROUPE_CHANGE",
                "new_groupe", "AUTORISE"));
    }

    @Test
    void demandeLabelIsReplacedByFirst40CharsOfDescription() {
        String description = "transformation et réaménagement d'un restaurant au 4ème étage   ";
        PushMessage m = PushMessages.forEvent(notification("APA", "--", description, Groupe.DEPOSE, 87));

        assertThat(m.title()).isEqualTo("Transformation et réaménagement d'un res… à 87 m");
        assertThat(m.body()).isEqualTo("Nouvelle demande déposée — délai d'opposition en cours. "
                + "Transformation et réaménagement d'un restaurant au 4ème étage");
        assertThat(m.data()).containsEntry("kind", "NEW").containsEntry("new_groupe", "DEPOSE");
    }

    @Test
    void shortDescriptionIsNotTruncated() {
        PushMessage m = PushMessages.forEvent(notification("APA", null, "pose d'une pergola", Groupe.CHANTIER, 12));

        assertThat(m.title()).isEqualTo("Pose d'une pergola à 12 m");
        assertThat(m.body()).isEqualTo("Les travaux commencent. Pose d'une pergola");
    }

    @Test
    void bodyDescriptionIsCappedAt90Chars() {
        String description = "a".repeat(120);
        PushMessage m = PushMessages.forEvent(notification("DD", "NOU", description, Groupe.RECOURS, 300));

        assertThat(m.body()).isEqualTo("Un recours a été déposé. A" + "a".repeat(89) + "…");
    }

    @Test
    void missingDescriptionKeepsDemandeAndShortBody() {
        PushMessage m = PushMessages.forEvent(notification("APA", "--", "   ", Groupe.REFUSE, 455.2));

        assertThat(m.title()).isEqualTo("Demande à 455 m");
        assertThat(m.body()).isEqualTo("Le projet ne se fera pas.");
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "M,    REN,  Démolition",
            "MPA,  null, Démolition",
            "DD,   VIL,  Nouvelle villa",
            "DD,   NOU,  Construction nouvelle",
            "DD,   AGR,  Agrandissement",
            "APA,  AGV,  Agrandissement de villa",
            "APA,  REN,  Rénovation",
            "APA,  PI,   Petite construction",
            "DD,   EQU,  Aménagement public",
            "DD,   AFF,  Changement d'affectation",
            "APA,  --,   Demande",
            "APA,  null, Demande",
            "APA,  XYZ,  Demande"
    })
    void operationLabelFollowsVocabulary(String typeDossier, String typeOperation, String expected) {
        assertThat(PushMessages.operationLabel(typeDossier, typeOperation)).isEqualTo(expected);
    }
}
