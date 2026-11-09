package ch.perimetre.parcelle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import ch.perimetre.sitg.ImportMode;

class ParcelleImporterRulesTest {

    @Test
    void theFirstImportIsFullWhateverWasAsked() {
        assertThat(ParcelleImporter.effectiveMode(ImportMode.INCREMENTAL, false)).isEqualTo(ImportMode.FULL);
        assertThat(ParcelleImporter.effectiveMode(ImportMode.FULL, false)).isEqualTo(ImportMode.FULL);
    }

    @Test
    void afterBootstrapTheRequestedModeRuns() {
        assertThat(ParcelleImporter.effectiveMode(ImportMode.INCREMENTAL, true)).isEqualTo(ImportMode.INCREMENTAL);
        assertThat(ParcelleImporter.effectiveMode(ImportMode.FULL, true)).isEqualTo(ImportMode.FULL);
    }

    @Test
    void aFullImportThatWouldWipeTheTableIsRefused() {
        assertThatThrownBy(() -> ParcelleImporter.checkNotShrunk(73_156, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refused");
        assertThatThrownBy(() -> ParcelleImporter.checkNotShrunk(73_156, 0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void normalShrinkageAndTheFirstImportAreAccepted() {
        assertThatCode(() -> ParcelleImporter.checkNotShrunk(73_156, 73_000)).doesNotThrowAnyException();
        assertThatCode(() -> ParcelleImporter.checkNotShrunk(73_156, 80_000)).doesNotThrowAnyException();
        assertThatCode(() -> ParcelleImporter.checkNotShrunk(0, 0)).doesNotThrowAnyException();
    }

    @Test
    void theIncrementalCursorStartsThreeDaysBackAtMidnightUtc() {
        assertThat(ParcelleImporter.cursor(java.time.Instant.parse("2026-11-09T09:30:15Z")))
                .isEqualTo(java.time.Instant.parse("2026-11-06T00:00:00Z"));
    }
}
