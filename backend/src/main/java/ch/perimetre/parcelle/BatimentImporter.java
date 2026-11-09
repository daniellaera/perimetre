package ch.perimetre.parcelle;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import ch.perimetre.sitg.ImportMode;

/**
 * Buildings above ground of the official survey ({@code CAD_BATIMENT_HORSOL}, SITG class A, ~83k polygons).
 * The building's own parcels come from the layer's {@code EGRID_LISTE}; the footprint inside each parcel is
 * measured on the geometries. INCREMENTAL by {@code DATEDT >= last_run - 3 days}; deletions only show in FULL.
 * Not read: NOMBAT (institution names), the permit number and the construction epoch.
 */
@Service
class BatimentImporter extends LayerImporter {

    private static final Logger log = LoggerFactory.getLogger(BatimentImporter.class);
    static final String SOURCE = "CAD_BATIMENT_HORSOL";
    static final String OUT_FIELDS = "EGID,COMMUNE,NO_BATIMENT,DESTINATION,NOMENCLATURE,SURFACE,"
            + "ANNEE_CONSTRUCTION,NIVEAUX_HORSOL,DATEDT,EGRID_LISTE";

    private final ArcgisLayerClient client;
    private final ContexteRepository repo;

    @Autowired
    BatimentImporter(ContexteRepository repo, JdbcClient jdbc, TransactionTemplate tx) {
        this(new ArcgisLayerClient(SOURCE, OUT_FIELDS, true), repo, jdbc, tx);
    }

    BatimentImporter(ArcgisLayerClient client, ContexteRepository repo, JdbcClient jdbc, TransactionTemplate tx) {
        super(jdbc, tx);
        this.client = client;
        this.repo = repo;
    }

    @Override
    String source() {
        return SOURCE;
    }

    @Override
    String jobName() {
        return "batiment";
    }

    @Override
    boolean supportsIncremental() {
        return true;
    }

    @Override
    Outcome importLayer(ImportMode mode, Instant since) {
        long before = repo.countBatiments();
        Set<Long> seen = new HashSet<>();
        List<Long> changed = new ArrayList<>();
        LocalDate[] max = new LocalDate[1];
        String where = since == null ? "1=1" : "DATEDT >= " + ArcgisLayerClient.timestampLiteral(since);
        long fetched = client.fetch(where, page -> {
            List<ContexteRows.Batiment> rows = toRows(page);
            for (ContexteRows.Batiment r : rows) {
                seen.add(r.egid());
                if (r.dateMut() != null && (max[0] == null || r.dateMut().isAfter(max[0]))) {
                    max[0] = r.dateMut();
                }
            }
            changed.addAll(repo.upsertBatiments(rows));
        });
        long removed = 0;
        if (mode == ImportMode.FULL) {
            checkNotShrunk("building", before, seen.size());
            removed = repo.deleteBatimentsExcept(seen);
            // Links are recomputed for what changed; a full pass over an unchanged table touches nothing.
            repo.relinkBatiments(before == 0 ? null : changed);
        } else {
            repo.relinkBatiments(changed);
        }
        return new Outcome(fetched, changed.size() + removed, removed, max[0]);
    }

    static List<ContexteRows.Batiment> toRows(List<LayerPage.Feature> features) {
        List<ContexteRows.Batiment> rows = new ArrayList<>(features.size());
        int skipped = 0;
        for (LayerPage.Feature f : features) {
            Long egid = f.number("EGID");
            if (egid == null || !f.hasGeometry()) {
                skipped++;
                continue;
            }
            rows.add(new ContexteRows.Batiment(egid, f.text("COMMUNE"), f.text("NO_BATIMENT"), f.text("DESTINATION"),
                    f.text("NOMENCLATURE"), f.integer("SURFACE"), f.integer("ANNEE_CONSTRUCTION"),
                    f.integer("NIVEAUX_HORSOL"), day(f.number("DATEDT")), f.text("EGRID_LISTE"),
                    f.geometry().toString()));
        }
        if (skipped > 0) {
            log.warn("Building import skipped {} features without EGID or geometry", skipped);
        }
        return rows;
    }
}
