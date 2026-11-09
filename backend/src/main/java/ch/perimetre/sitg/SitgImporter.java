package ch.perimetre.sitg;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import ch.perimetre.dossier.Dossier;
import ch.perimetre.dossier.DossierRepository;
import ch.perimetre.dossier.Groupe;
import ch.perimetre.event.EventRepository;
import ch.perimetre.event.NewEvent;
import ch.perimetre.parcelle.DossierLinker;

@Service
public class SitgImporter {

    private static final Logger log = LoggerFactory.getLogger(SitgImporter.class);

    static final String SOURCE = "SIT_AUTOR_DOSSIER";
    // date_maj_2 is date-only and a day's batch is published the next morning; 3 days of overlap absorb that and
    // a publication up to 2 days late (LatePublicationTest).
    private static final Duration CURSOR_OVERLAP = Duration.ofDays(3);
    // Safety net: the weekly full pass re-reads everything modified since then, whatever the cursor missed.
    private static final Instant FULL_SINCE = Instant.parse("2025-01-01T00:00:00Z");

    private final SitgClient client;
    private final DossierRepository dossiers;
    private final EventRepository events;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final DossierLinker linker;

    SitgImporter(SitgClient client, DossierRepository dossiers, EventRepository events, JdbcClient jdbc,
            TransactionTemplate tx, DossierLinker linker) {
        this.linker = linker;
        this.client = client;
        this.dossiers = dossiers;
        this.events = events;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /**
     * {@code eventIds} are the events created by this run, for the notification dispatcher;
     * {@code maxDateMaj} the newest {@code date_maj_2} fetched (null when nothing was), for the freshness check.
     */
    public record ImportResult(ImportMode mode, long fetched, long inserted, long updated, List<Long> eventIds,
            LocalDate maxDateMaj, long durationMs) {
    }

    private record SyncState(Instant lastRun, boolean bootstrapped) {
    }

    /**
     * Fetches the dossiers for {@code mode}, detects events against the stored groupes, upserts, inserts the
     * events and advances the cursor, all in one transaction. Any failure propagates and leaves the cursor
     * untouched. The first run on an empty database ({@code bootstrapped = false}) emits no events.
     */
    public ImportResult run(ImportMode mode) {
        long start = System.nanoTime();
        SyncState state = jdbc.sql("SELECT last_run, bootstrapped FROM sync_state WHERE source = ?")
                .param(SOURCE)
                .query((rs, rowNum) -> new SyncState(
                        rs.getObject("last_run", OffsetDateTime.class).toInstant(), rs.getBoolean("bootstrapped")))
                .single();
        try {
            Instant since = mode == ImportMode.FULL ? FULL_SINCE : cursor(state.lastRun());
            log.info("SITG {} import starting, last_run={} since={} bootstrapped={}",
                    mode, state.lastRun(), since, state.bootstrapped());

            List<SitgPage.Feature> features = client.fetchModifiedSince(since);
            List<Dossier> rows = toDossiers(features);

            ImportResult result = tx.execute(status -> {
                Map<String, Groupe> existing = dossiers.findGroupes(rows.stream().map(Dossier::idDossier).toList());
                List<NewEvent> detected = ChangeDetector.detect(existing, rows, state.bootstrapped());
                dossiers.upsert(rows);
                List<Long> eventIds = events.insert(detected);
                jdbc.sql("UPDATE sync_state SET last_run = now(), bootstrapped = true WHERE source = ?")
                        .param(SOURCE)
                        .update();
                return new ImportResult(mode, features.size(), rows.size() - existing.size(), existing.size(),
                        eventIds, maxDateMaj(rows), elapsedMs(start));
            });
            linkParcels(rows);
            log.info("SITG import done: mode={} fetched={} inserted={} updated={} events={}{} durationMs={}",
                    mode, result.fetched(), result.inserted(), result.updated(), result.eventIds().size(),
                    state.bootstrapped() ? "" : " (bootstrap run, events suppressed)", result.durationMs());
            return result;
        } catch (RuntimeException e) {
            log.error("SITG {} import failed after {} ms; cursor left at {}", mode, elapsedMs(start), state.lastRun(), e);
            throw e;
        }
    }

    /**
     * Links the imported dossiers to their parcels, after the import's own transaction: a failure here must
     * not undo the import or its notifications. The next parcel import links the dossiers left without a
     * link (ParcelleRepository.relinkAffectedBy).
     */
    private void linkParcels(List<Dossier> rows) {
        try {
            linker.link(rows.stream().map(Dossier::idDossier).toList());
        } catch (RuntimeException e) {
            log.warn("Linking {} dossiers to their parcels failed; the next parcel import repairs it", rows.size(), e);
        }
    }

    /** The incremental query's lower bound on {@code date_maj_2}, given the previous run's {@code last_run}. */
    static Instant cursor(Instant lastRun) {
        return lastRun.minus(CURSOR_OVERLAP);
    }

    /** The newest {@code date_maj_2} (date-only, midnight UTC) among {@code rows}, null when there are none. */
    static LocalDate maxDateMaj(List<Dossier> rows) {
        return rows.stream()
                .map(d -> d.dateMajSitg().withOffsetSameInstant(ZoneOffset.UTC).toLocalDate())
                .max(LocalDate::compareTo)
                .orElse(null);
    }

    private static List<Dossier> toDossiers(List<SitgPage.Feature> features) {
        // Keyed by id_dossier: offset paging can return a dossier twice; the last occurrence wins.
        Map<String, Dossier> rows = new LinkedHashMap<>();
        int skipped = 0;
        for (SitgPage.Feature feature : features) {
            SitgPage.Attributes a = feature.properties();
            SitgPage.Geometry g = feature.geometry();
            if (a == null || a.idDossier() == null || a.typeDossier() == null || a.dateMaj2() == null
                    || g == null || g.coordinates() == null || g.coordinates().size() < 2) {
                skipped++;
                continue;
            }
            rows.put(a.idDossier(), new Dossier(
                    a.idDossier(),
                    a.typeDossier(),
                    a.typeOperation(),
                    a.operation(),
                    a.description(),
                    a.statut(),
                    SitgMapping.groupe(a.statut()),
                    a.dateDepot() == null ? null : LocalDate.ofInstant(Instant.ofEpochMilli(a.dateDepot()), ZoneOffset.UTC),
                    Instant.ofEpochMilli(a.dateMaj2()).atOffset(ZoneOffset.UTC),
                    a.lienSad(),
                    g.coordinates().get(0),
                    g.coordinates().get(1),
                    SitgMapping.flags(a.description())));
        }
        if (skipped > 0) {
            log.warn("SITG import skipped {} features missing id, type, date_maj_2 or geometry", skipped);
        }
        return new ArrayList<>(rows.values());
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
