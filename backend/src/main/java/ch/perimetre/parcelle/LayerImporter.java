package ch.perimetre.parcelle;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import ch.perimetre.sitg.ImportMode;

/**
 * One open layer around the parcels (buildings, addresses, zones, local names), imported like the parcels:
 * one transaction, cursor in {@code sync_state}, the first run always FULL, a FULL refused when the source
 * returns under 90% of what is stored. Subclasses only fetch, write and relink.
 */
abstract class LayerImporter {

    private static final Duration CURSOR_OVERLAP = Duration.ofDays(3);

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    LayerImporter(JdbcClient jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** The {@code sync_state} source, also the name in the logs. */
    abstract String source();

    /** The {@code job_run} name is {@code jobName-incremental|full}. */
    abstract String jobName();

    /** True when the layer carries a mutation date, so a daily INCREMENTAL makes sense. */
    abstract boolean supportsIncremental();

    /**
     * Runs inside the transaction. {@code since} is the incremental lower bound (null = everything).
     * Must return what it did; may throw to roll everything back.
     */
    abstract Outcome importLayer(ImportMode mode, Instant since);

    record Outcome(long fetched, long changed, long removed, LocalDate maxDate) {
    }

    record Result(ImportMode mode, long fetched, long changed, long removed, LocalDate maxDate, long durationMs) {
    }

    private record SyncState(Instant lastRun, boolean bootstrapped) {
    }

    private SyncState syncState() {
        return jdbc.sql("SELECT last_run, bootstrapped FROM sync_state WHERE source = ?").param(source())
                .query((rs, n) -> new SyncState(rs.getObject("last_run", OffsetDateTime.class).toInstant(),
                        rs.getBoolean("bootstrapped")))
                .single();
    }

    /** The mode that will really run: FULL when requested, when the layer has no date, and for the first import. */
    ImportMode effectiveMode(ImportMode requested) {
        return effectiveMode(requested, supportsIncremental(), syncState().bootstrapped());
    }

    static ImportMode effectiveMode(ImportMode requested, boolean supportsIncremental, boolean bootstrapped) {
        return requested == ImportMode.FULL || !supportsIncremental || !bootstrapped
                ? ImportMode.FULL : ImportMode.INCREMENTAL;
    }

    /** {@code last_run - 3 days} from midnight UTC (the layers' dates are day-granular). */
    static Instant cursor(Instant lastRun) {
        return lastRun.minus(CURSOR_OVERLAP).truncatedTo(ChronoUnit.DAYS);
    }

    /** A full import returning fewer than 90% of what is stored is a broken source, not a change. */
    static void checkNotShrunk(String what, long stored, long fetchedDistinct) {
        if (stored > 0 && fetchedDistinct < stored * 0.9) {
            throw new IllegalStateException(what + " full import refused: the source returned " + fetchedDistinct
                    + " distinct rows, the table has " + stored + " (more than 10% would be deleted)");
        }
    }

    Result run(ImportMode requested) {
        long start = System.nanoTime();
        SyncState state = syncState();
        ImportMode mode = effectiveMode(requested, supportsIncremental(), state.bootstrapped());
        try {
            Instant since = mode == ImportMode.FULL ? null : cursor(state.lastRun());
            log.info("{} {} import starting (requested {}), since={} bootstrapped={}", source(), mode, requested,
                    since, state.bootstrapped());
            Outcome o = tx.execute(status -> {
                Outcome outcome = importLayer(mode, since);
                jdbc.sql("UPDATE sync_state SET last_run = now(), bootstrapped = true WHERE source = ?")
                        .param(source()).update();
                return outcome;
            });
            Result r = new Result(mode, o.fetched(), o.changed(), o.removed(), o.maxDate(), elapsed(start));
            log.info("{} import done: mode={} fetched={} changed={} removed={} durationMs={}", source(), mode,
                    r.fetched(), r.changed(), r.removed(), r.durationMs());
            return r;
        } catch (RuntimeException e) {
            log.error("{} {} import failed after {} ms; cursor left at {}", source(), mode, elapsed(start),
                    state.lastRun(), e);
            throw e;
        }
    }

    static LocalDate day(Long epochMs) {
        return epochMs == null ? null : LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }

    private static long elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
