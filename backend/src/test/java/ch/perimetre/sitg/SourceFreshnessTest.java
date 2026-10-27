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

/** The newest date_maj_2 of a run and the 4-day staleness rule (ImportJob ends such a run STALE_SOURCE). */
class SourceFreshnessTest {

    private static final LocalDate D = LocalDate.of(2026, 10, 26);

    @Test
    void staleOnlyWhenTheNewestDateIsMoreThanFourDaysBeforeTheRun() {
        Instant fourDaysAfter = Instant.parse("2026-10-30T00:00:00Z");
        assertThat(ImportJob.isStale(D, Instant.parse("2026-10-27T09:15:00Z"))).isFalse(); // a normal 10:15 run
        // 2026-10-13 10:15: newest stamp 10-10 after a 3-day gap in SITG's stamps, not an outage.
        assertThat(ImportJob.isStale(LocalDate.of(2026, 10, 10), Instant.parse("2026-10-13T08:15:00Z"))).isFalse();
        assertThat(ImportJob.isStale(D, fourDaysAfter)).isFalse();
        assertThat(ImportJob.isStale(D, fourDaysAfter.plusSeconds(1))).isTrue();
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
