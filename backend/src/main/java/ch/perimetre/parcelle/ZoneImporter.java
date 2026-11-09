package ch.perimetre.parcelle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import ch.perimetre.sitg.ImportMode;

/**
 * Land-use zones published openly in the RDPPF (public-law restrictions) series: {@code RDPPF_ZONES_PRIM}
 * (zones d'affectation) and {@code RDPPF_ZONES_DEV} (zones de développement), SITG class A, ~3.2k polygons.
 * Always FULL and replaced as a whole (the layers are small, have no stable key); the parcels' zones are then
 * recomputed. The legal documents' links (LIEN_*) are not stored.
 */
@Service
class ZoneImporter extends LayerImporter {

    static final String SOURCE = "RDPPF_ZONES";
    static final String PRIM = "RDPPF_ZONES_PRIM";
    static final String DEV = "RDPPF_ZONES_DEV";
    static final String OUT_FIELDS = "ABRV_ZONE,NOM_ZONE,DESC_ZONE,COMMUNE,STATUT_JUR";

    private final ArcgisLayerClient prim;
    private final ArcgisLayerClient dev;
    private final ContexteRepository repo;

    @Autowired
    ZoneImporter(ContexteRepository repo, JdbcClient jdbc, TransactionTemplate tx) {
        this(new ArcgisLayerClient(PRIM, OUT_FIELDS, true), new ArcgisLayerClient(DEV, OUT_FIELDS, true), repo, jdbc,
                tx);
    }

    ZoneImporter(ArcgisLayerClient prim, ArcgisLayerClient dev, ContexteRepository repo, JdbcClient jdbc,
            TransactionTemplate tx) {
        super(jdbc, tx);
        this.prim = prim;
        this.dev = dev;
        this.repo = repo;
    }

    @Override
    String source() {
        return SOURCE;
    }

    @Override
    String jobName() {
        return "zone";
    }

    @Override
    boolean supportsIncremental() {
        return false;
    }

    @Override
    Outcome importLayer(ImportMode mode, Instant since) {
        long before = repo.countZones();
        List<ContexteRows.Zone> rows = new ArrayList<>();
        long fetched = prim.fetch("1=1", page -> rows.addAll(toRows("ZONE", page)));
        fetched += dev.fetch("1=1", page -> rows.addAll(toRows("ZONE_DEVELOPPEMENT", page)));
        checkNotShrunk("zone", before, rows.size());
        repo.replaceZones(rows);
        repo.relinkZones(null);
        return new Outcome(fetched, rows.size(), Math.max(0, before - rows.size()), null);
    }

    static List<ContexteRows.Zone> toRows(String kind, List<LayerPage.Feature> features) {
        List<ContexteRows.Zone> rows = new ArrayList<>(features.size());
        for (LayerPage.Feature f : features) {
            if (!f.hasGeometry() || f.text("NOM_ZONE") == null) {
                continue;
            }
            rows.add(new ContexteRows.Zone(kind, f.text("ABRV_ZONE"), f.text("NOM_ZONE"), f.text("DESC_ZONE"),
                    f.text("COMMUNE"), f.text("STATUT_JUR"), f.geometry().toString()));
        }
        return rows;
    }
}
