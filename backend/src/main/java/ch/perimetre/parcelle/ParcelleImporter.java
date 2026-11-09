package ch.perimetre.parcelle;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import ch.perimetre.sitg.ImportMode;

/**
 * Imports SITG's cadastral parcels ({@code CAD_PARCELLE_MENSU}, parcels in force) and keeps
 * {@code dossier_parcelle} up to date, all in one transaction (any failure writes nothing and leaves the cursor).
 * <ul>
 * <li>FULL: every parcel; parcels gone from the source are deleted (refused if more than 10% would vanish);
 * all links are recomputed. The first run is always FULL ({@code bootstrapped = false}).</li>
 * <li>INCREMENTAL: parcels with {@code DATEDT >= last_run - 3 days} (DATEDT is the last mutation and is
 * date-only, like SITG's dossier stamps); only the links a changed parcel can affect are recomputed.
 * Deletions are invisible to it: the weekly full pass handles them.</li>
 * </ul>
 */
@Service
public class ParcelleImporter {

    private static final Logger log = LoggerFactory.getLogger(ParcelleImporter.class);

    static final String SOURCE = "CAD_PARCELLE_MENSU";
    private static final Duration CURSOR_OVERLAP = Duration.ofDays(3);
    private static final double MIN_KEPT_RATIO = 0.9;

    private final ParcelleClient client;
    private final ParcelleRepository parcelles;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    ParcelleImporter(ParcelleClient client, ParcelleRepository parcelles, JdbcClient jdbc, TransactionTemplate tx) {
        this.client = client;
        this.parcelles = parcelles;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /**
     * {@code mode} is the mode actually run (FULL when the first import was requested as incremental);
     * {@code changed} = parcels inserted or modified; {@code removed} = parcels deleted (FULL);
     * {@code maxDateMut} = newest DATEDT fetched.
     */
    public record ImportResult(ImportMode mode, long fetched, long changed, long removed, long relinked,
            LocalDate maxDateMut, long durationMs) {
    }

    private record SyncState(Instant lastRun, boolean bootstrapped) {
    }

    private SyncState syncState() {
        return jdbc.sql("SELECT last_run, bootstrapped FROM sync_state WHERE source = ?")
                .param(SOURCE)
                .query((rs, rowNum) -> new SyncState(
                        rs.getObject("last_run", OffsetDateTime.class).toInstant(), rs.getBoolean("bootstrapped")))
                .single();
    }

    /** The mode that will really run: FULL when requested, and always for the very first import. */
    public ImportMode effectiveMode(ImportMode requested) {
        return effectiveMode(requested, syncState().bootstrapped());
    }

    static ImportMode effectiveMode(ImportMode requested, boolean bootstrapped) {
        return requested == ImportMode.FULL || !bootstrapped ? ImportMode.FULL : ImportMode.INCREMENTAL;
    }

    /** A full import that would delete more than 10% of the stored parcels is a broken source, not a change. */
    static void checkNotShrunk(long stored, long fetchedDistinct) {
        if (stored > 0 && fetchedDistinct < stored * MIN_KEPT_RATIO) {
            throw new IllegalStateException("parcel full import refused: the source returned " + fetchedDistinct
                    + " distinct parcels, the table has " + stored + " (more than 10% would be deleted)");
        }
    }

    public ImportResult run(ImportMode requested) {
        long start = System.nanoTime();
        SyncState state = syncState();
        ImportMode mode = effectiveMode(requested, state.bootstrapped());
        try {
            Instant since = mode == ImportMode.FULL ? null : cursor(state.lastRun());
            log.info("Parcel {} import starting (requested {}), last_run={} since={} bootstrapped={}",
                    mode, requested, state.lastRun(), since, state.bootstrapped());

            ImportResult result = tx.execute(status -> {
                long before = parcelles.count();
                List<String> changed = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                LocalDate[] maxDate = new LocalDate[1];
                long fetched = client.fetch(since, page -> {
                    List<ParcelleRow> rows = toRows(page);
                    for (ParcelleRow r : rows) {
                        seen.add(r.idParcelle());
                        if (r.dateMut() != null && (maxDate[0] == null || r.dateMut().isAfter(maxDate[0]))) {
                            maxDate[0] = r.dateMut();
                        }
                    }
                    changed.addAll(parcelles.upsert(rows));
                });
                long removed = 0;
                long relinked;
                if (mode == ImportMode.FULL) {
                    checkNotShrunk(before, seen.size());
                    removed = parcelles.deleteExcept(seen);
                    relinked = parcelles.relinkAll();
                } else {
                    relinked = changed.isEmpty() ? 0 : parcelles.relinkAffectedBy(changed);
                }
                jdbc.sql("UPDATE sync_state SET last_run = now(), bootstrapped = true WHERE source = ?")
                        .param(SOURCE)
                        .update();
                return new ImportResult(mode, fetched, changed.size() + removed, removed, relinked, maxDate[0],
                        elapsedMs(start));
            });
            log.info("Parcel import done: mode={} fetched={} changed={} removed={} {}={} durationMs={}",
                    mode, result.fetched(), result.changed() - result.removed(), result.removed(),
                    mode == ImportMode.FULL ? "links" : "dossiersRelinked", result.relinked(), result.durationMs());
            return result;
        } catch (RuntimeException e) {
            log.error("Parcel {} import failed after {} ms; cursor left at {}", mode, elapsedMs(start),
                    state.lastRun(), e);
            throw e;
        }
    }

    /**
     * The incremental query's lower bound on DATEDT: {@code last_run - 3 days}, from the start of that day
     * (DATEDT is date-only, midnight UTC: a time of day would drop the stamps of the cursor's own day).
     */
    static Instant cursor(Instant lastRun) {
        return lastRun.minus(CURSOR_OVERLAP).truncatedTo(ChronoUnit.DAYS);
    }

    private static List<ParcelleRow> toRows(List<ParcellePage.Feature> features) {
        List<ParcelleRow> rows = new ArrayList<>(features.size());
        int skipped = 0;
        for (ParcellePage.Feature f : features) {
            ParcellePage.Attributes a = f.properties();
            if (a == null || a.noComm() == null || a.noParcelle() == null || a.commune() == null
                    || f.geometry() == null || f.geometry().isNull() || f.geometry().isMissingNode()) {
                skipped++;
                continue;
            }
            String id = a.ideddp() != null && !a.ideddp().isBlank() ? a.ideddp() : a.noComm() + ":" + a.noParcelle();
            rows.add(new ParcelleRow(id, a.egrid(), a.commune(), a.noComm(), a.noParcelle(), a.surface(),
                    a.datedt() == null ? null : LocalDate.ofInstant(Instant.ofEpochMilli(a.datedt()), ZoneOffset.UTC),
                    a.lienWww(), f.geometry().toString()));
        }
        if (skipped > 0) {
            log.warn("Parcel import skipped {} features missing commune, number or geometry", skipped);
        }
        return rows;
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
