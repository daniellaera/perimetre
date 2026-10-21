package ch.perimetre.sitg;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import ch.perimetre.dossier.Dossier;
import ch.perimetre.dossier.DossierRepository;

@Service
public class SitgImporter {

    private static final Logger log = LoggerFactory.getLogger(SitgImporter.class);

    static final String SOURCE = "SIT_AUTOR_DOSSIER";
    // date_maj_2 is date-only and lags publication by ~1 day; 3 days of overlap absorbs that.
    private static final Duration CURSOR_OVERLAP = Duration.ofDays(3);

    private final SitgClient client;
    private final DossierRepository dossiers;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    SitgImporter(SitgClient client, DossierRepository dossiers, JdbcClient jdbc, TransactionTemplate tx) {
        this.client = client;
        this.dossiers = dossiers;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public record ImportResult(long fetched, long inserted, long updated, long durationMs) {
    }

    /**
     * Fetches everything modified since {@code last_run - 3 days}, upserts it and advances the cursor,
     * all in one transaction. Any failure propagates and leaves the cursor untouched.
     */
    public ImportResult run() {
        long start = System.nanoTime();
        Instant lastRun = jdbc.sql("SELECT last_run FROM sync_state WHERE source = ?")
                .param(SOURCE)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
        try {
            Instant cursor = lastRun.minus(CURSOR_OVERLAP);
            log.info("SITG import starting, last_run={} cursor={}", lastRun, cursor);

            List<SitgPage.Feature> features = client.fetchModifiedSince(cursor);
            List<Dossier> rows = toDossiers(features);

            ImportResult result = tx.execute(status -> {
                long before = dossiers.count();
                dossiers.upsert(rows);
                long inserted = dossiers.count() - before;
                jdbc.sql("UPDATE sync_state SET last_run = now() WHERE source = ?").param(SOURCE).update();
                return new ImportResult(features.size(), inserted, rows.size() - inserted, elapsedMs(start));
            });
            log.info("SITG import done: {}", result);
            return result;
        } catch (RuntimeException e) {
            log.error("SITG import failed after {} ms; cursor left at {}", elapsedMs(start), lastRun, e);
            throw e;
        }
    }

    private static List<Dossier> toDossiers(List<SitgPage.Feature> features) {
        List<Dossier> rows = new ArrayList<>(features.size());
        int skipped = 0;
        for (SitgPage.Feature feature : features) {
            SitgPage.Attributes a = feature.properties();
            SitgPage.Geometry g = feature.geometry();
            if (a == null || a.idDossier() == null || a.typeDossier() == null || a.dateMaj2() == null
                    || g == null || g.coordinates() == null || g.coordinates().size() < 2) {
                skipped++;
                continue;
            }
            rows.add(new Dossier(
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
        return rows;
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
