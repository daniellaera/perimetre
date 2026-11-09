package ch.perimetre.parcelle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import ch.perimetre.sitg.ImportMode;

/** Local names / lieux-dits ({@code CAD_NOMS_LOCAUX}, SITG class A, ~2k polygons). Always FULL, replaced whole. */
@Service
class LieuDitImporter extends LayerImporter {

    static final String SOURCE = "CAD_NOMS_LOCAUX";
    static final String OUT_FIELDS = "TEXTE";

    private final ArcgisLayerClient client;
    private final ContexteRepository repo;

    @Autowired
    LieuDitImporter(ContexteRepository repo, JdbcClient jdbc, TransactionTemplate tx) {
        this(new ArcgisLayerClient(SOURCE, OUT_FIELDS, true), repo, jdbc, tx);
    }

    LieuDitImporter(ArcgisLayerClient client, ContexteRepository repo, JdbcClient jdbc, TransactionTemplate tx) {
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
        return "lieu-dit";
    }

    @Override
    boolean supportsIncremental() {
        return false;
    }

    @Override
    Outcome importLayer(ImportMode mode, Instant since) {
        long before = repo.countLieuxDits();
        List<ContexteRows.LieuDit> rows = new ArrayList<>();
        long fetched = client.fetch("1=1", page -> rows.addAll(toRows(page)));
        checkNotShrunk("local name", before, rows.size());
        repo.replaceLieuxDits(rows);
        repo.relinkLieuxDits(null);
        return new Outcome(fetched, rows.size(), Math.max(0, before - rows.size()), null);
    }

    static List<ContexteRows.LieuDit> toRows(List<LayerPage.Feature> features) {
        List<ContexteRows.LieuDit> rows = new ArrayList<>(features.size());
        for (LayerPage.Feature f : features) {
            if (f.hasGeometry() && f.text("TEXTE") != null) {
                rows.add(new ContexteRows.LieuDit(f.text("TEXTE"), f.geometry().toString()));
            }
        }
        return rows;
    }
}
