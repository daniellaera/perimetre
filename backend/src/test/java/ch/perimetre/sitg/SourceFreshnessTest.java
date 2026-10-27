package ch.perimetre.sitg;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import ch.perimetre.dossier.Dossier;
import ch.perimetre.dossier.Groupe;

/** The newest date_maj_2 of a run and the 3-day staleness rule (ImportJob ends such a run STALE_SOURCE). */
class SourceFreshnessTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 26);

    @Test
    void staleOnlyWhenTheNewestDateIsMoreThanThreeDaysBeforeTheRun() {
        Instant threeDaysAfter = Instant.parse("2026-10-29T00:00:00Z");
        assertThat(ImportJob.isStale(D, Instant.parse("2026-10-27T09:15:00Z"))).isFalse(); // a normal 10:15 run
        assertThat(ImportJob.isStale(D, threeDaysAfter)).isFalse();
        assertThat(ImportJob.isStale(D, threeDaysAfter.plusSeconds(1))).isTrue();
        assertThat(ImportJob.isStale(null, Instant.parse("2026-10-27T09:15:00Z"))).isTrue(); // nothing fetched
    }

    @Test
    void maxDateMajIsTheNewestUtcDate() {
        assertThat(SitgImporter.maxDateMaj(List.of())).isNull();
        assertThat(SitgImporter.maxDateMaj(List.of(
                dossier("2026-10-24T00:00:00Z"), dossier("2026-10-26T00:00:00Z"), dossier("2026-10-25T00:00:00Z"))))
                .isEqualTo(D);
    }

    private static Dossier dossier(String dateMaj) {
        return new Dossier("APA 1/1", "APA", "--", null, null, "ENREGISTREMENT", Groupe.DEPOSE, D,
                OffsetDateTime.ofInstant(Instant.parse(dateMaj), ZoneOffset.UTC), null, 6.15, 46.2, List.of());
    }
}
