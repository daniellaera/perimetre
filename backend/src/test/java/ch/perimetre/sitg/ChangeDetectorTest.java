package ch.perimetre.sitg;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import ch.perimetre.dossier.Dossier;
import ch.perimetre.dossier.Groupe;
import ch.perimetre.event.EventKind;
import ch.perimetre.event.NewEvent;

class ChangeDetectorTest {

    private static Dossier dossier(String id, Groupe groupe) {
        return new Dossier(id, "APA", "--", "Non renseigné", "pose d'une pergola", "X", groupe,
                LocalDate.of(2026, 10, 1), OffsetDateTime.of(2026, 10, 20, 0, 0, 0, 0, ZoneOffset.UTC),
                null, 6.14, 46.19, List.of());
    }

    @Test
    void newDossierInDeposeIsNew() {
        List<NewEvent> events = ChangeDetector.detect(Map.of(), List.of(dossier("A", Groupe.DEPOSE)), true);

        assertThat(events).containsExactly(new NewEvent("A", EventKind.NEW, null, Groupe.DEPOSE));
    }

    @Test
    void groupeChangeOfExistingDossierIsGroupeChange() {
        List<NewEvent> events = ChangeDetector.detect(
                Map.of("A", Groupe.DEPOSE), List.of(dossier("A", Groupe.CHANTIER)), true);

        assertThat(events).containsExactly(new NewEvent("A", EventKind.GROUPE_CHANGE, Groupe.DEPOSE, Groupe.CHANTIER));
    }

    @ParameterizedTest
    @EnumSource(value = Groupe.class, names = {"DEPOSE", "AUTORISE", "CHANTIER", "RECOURS", "REFUSE"})
    void changeToTermineIsSilent(Groupe before) {
        assertThat(ChangeDetector.detect(Map.of("A", before), List.of(dossier("A", Groupe.TERMINE)), true)).isEmpty();
    }

    @Test
    void changeToIgnoreIsSilent() {
        assertThat(ChangeDetector.detect(
                Map.of("A", Groupe.AUTORISE), List.of(dossier("A", Groupe.IGNORE)), true)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = Groupe.class, names = "DEPOSE", mode = EnumSource.Mode.EXCLUDE)
    void newDossierOutsideDeposeIsSilent(Groupe groupe) {
        assertThat(ChangeDetector.detect(Map.of(), List.of(dossier("A", groupe)), true)).isEmpty();
    }

    @Test
    void notBootstrappedEmitsNothing() {
        List<Dossier> incoming = List.of(dossier("A", Groupe.DEPOSE), dossier("B", Groupe.CHANTIER));

        assertThat(ChangeDetector.detect(Map.of("B", Groupe.DEPOSE), incoming, false)).isEmpty();
    }

    @Test
    void unchangedGroupeIsSilent() {
        assertThat(ChangeDetector.detect(
                Map.of("A", Groupe.CHANTIER), List.of(dossier("A", Groupe.CHANTIER)), true)).isEmpty();
    }

    @Test
    void mixedBatchEmitsOnlyRelevantEventsInInputOrder() {
        List<Dossier> incoming = List.of(
                dossier("new-depose", Groupe.DEPOSE),
                dossier("new-autorise", Groupe.AUTORISE),
                dossier("same", Groupe.AUTORISE),
                dossier("to-recours", Groupe.RECOURS),
                dossier("to-termine", Groupe.TERMINE));
        Map<String, Groupe> existing = Map.of(
                "same", Groupe.AUTORISE,
                "to-recours", Groupe.AUTORISE,
                "to-termine", Groupe.CHANTIER);

        assertThat(ChangeDetector.detect(existing, incoming, true)).containsExactly(
                new NewEvent("new-depose", EventKind.NEW, null, Groupe.DEPOSE),
                new NewEvent("to-recours", EventKind.GROUPE_CHANGE, Groupe.AUTORISE, Groupe.RECOURS));
    }
}
