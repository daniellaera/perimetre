package ch.perimetre.sitg;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.support.CronExpression;

/**
 * A late SITG publication costs latency, not data: with the configured crons (application.yml, Europe/Zurich)
 * and the importer's real cursor (last_run - 3 days), the batch stamped day D is fetched by the first
 * incremental run after its publication as long as SITG publishes it before the run of D+3, i.e. up to two
 * days later than usual (normally the morning of D+1). Every day of 2026 is checked, both DST switches
 * included.
 */
class LatePublicationTest {

    private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");
    private static final LocalDate FIRST = LocalDate.of(2026, 1, 1);
    private static final LocalDate LAST = LocalDate.of(2026, 12, 31);

    private static CronExpression incremental;
    private static CronExpression full;

    @BeforeAll
    static void loadCrons() throws IOException {
        var env = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);
        incremental = CronExpression.parse(env.getRequiredProperty("perimetre.import.incremental-cron"));
        full = CronExpression.parse(env.getRequiredProperty("perimetre.import.full-cron"));
    }

    @Test
    void theIncrementalRunsOnceEveryDay() {
        for (LocalDate day = FIRST; !day.isAfter(LAST); day = day.plusDays(1)) {
            ZonedDateTime run = incrementalRunOn(day);
            assertThat(incremental.next(run)).as("run after %s", run).isEqualTo(incrementalRunOn(day.plusDays(1)));
        }
    }

    @Test
    void aBatchPublishedRightAfterTheRunIsFetchedTheNextDay() {
        // 2026-10-27: the 03:15 run came before SITG published the 10-26 batch.
        for (LocalDate stamp = FIRST; !stamp.isAfter(LAST); stamp = stamp.plusDays(1)) {
            assertThat(fetched(stamp, incrementalRunOn(stamp.plusDays(1)).plusMinutes(1))).as("stamp %s", stamp).isTrue();
        }
    }

    @Test
    void aBatchPublishedUpToTwoDaysLateIsStillFetched() {
        for (LocalDate stamp = FIRST; !stamp.isAfter(LAST); stamp = stamp.plusDays(1)) {
            assertThat(fetched(stamp, incrementalRunOn(stamp.plusDays(3)).minusMinutes(1))).as("stamp %s", stamp).isTrue();
        }
    }

    @Test
    void laterThanThatOnlyTheWeeklyFullRefreshCatchesIt() {
        for (LocalDate stamp = FIRST; !stamp.isAfter(LAST); stamp = stamp.plusDays(1)) {
            assertThat(fetched(stamp, incrementalRunOn(stamp.plusDays(3)).plusMinutes(1))).as("stamp %s", stamp).isFalse();
        }
    }

    @Test
    void theFullRefreshRunsWeeklyAfterThatMorningsIncremental() {
        List<ZonedDateTime> runs = new ArrayList<>();
        for (ZonedDateTime run = full.next(FIRST.atStartOfDay(ZURICH)); run.toLocalDate().isBefore(LAST);
                run = full.next(run)) {
            runs.add(run);
            // The incremental takes ~1 s and the full ~15 s: 10 minutes keeps them clear of the import lock.
            assertThat(Duration.between(incrementalRunOn(run.toLocalDate()), run)).as("full run %s", run)
                    .isGreaterThanOrEqualTo(Duration.ofMinutes(10));
        }
        assertThat(runs).hasSizeBetween(52, 53);
    }

    /** Whether the first incremental run after {@code published} fetches the rows stamped {@code stamp}. */
    private static boolean fetched(LocalDate stamp, ZonedDateTime published) {
        ZonedDateTime run = incremental.next(published);
        // last_run is set when the previous run commits, a few seconds after it starts (a failed run leaves the
        // older last_run, which only widens the window).
        Instant lastRun = incrementalRunOn(run.toLocalDate().minusDays(1)).toInstant().plusSeconds(60);
        Instant dateMaj2 = stamp.atStartOfDay(ZoneOffset.UTC).toInstant(); // date-only, midnight UTC
        return !dateMaj2.isBefore(SitgImporter.cursor(lastRun));
    }

    private static ZonedDateTime incrementalRunOn(LocalDate day) {
        ZonedDateTime run = incremental.next(day.atStartOfDay(ZURICH).minusSeconds(1));
        assertThat(run.toLocalDate()).as("incremental run on %s", day).isEqualTo(day);
        return run;
    }
}
