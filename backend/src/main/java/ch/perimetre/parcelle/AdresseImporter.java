package ch.perimetre.parcelle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import ch.perimetre.sitg.ImportMode;

/**
 * Georeferenced building addresses ({@code CAD_ADRESSE}, SITG class A, ~55k points; no geometry is read, the
 * address reaches a building through its EGID). The layer has no date: always FULL (upsert of what differs,
 * delete of what vanished).
 */
@Service
class AdresseImporter extends LayerImporter {

    static final String SOURCE = "CAD_ADRESSE";
    static final String OUT_FIELDS = "IDPADR,EGID,ADRESSE,NO_POSTAL,NOM_NPA,POSTALE";

    private final ArcgisLayerClient client;
    private final ContexteRepository repo;

    @Autowired
    AdresseImporter(ContexteRepository repo, JdbcClient jdbc, TransactionTemplate tx) {
        this(new ArcgisLayerClient(SOURCE, OUT_FIELDS, false), repo, jdbc, tx);
    }

    AdresseImporter(ArcgisLayerClient client, ContexteRepository repo, JdbcClient jdbc, TransactionTemplate tx) {
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
        return "adresse";
    }

    @Override
    boolean supportsIncremental() {
        return false;
    }

    @Override
    Outcome importLayer(ImportMode mode, Instant since) {
        long before = repo.countAdresses();
        Set<String> seen = new HashSet<>();
        List<String> changed = new ArrayList<>();
        long fetched = client.fetch("1=1", page -> {
            List<ContexteRows.Adresse> rows = toRows(page);
            rows.forEach(r -> seen.add(r.idpadr()));
            changed.addAll(repo.upsertAdresses(rows));
        });
        checkNotShrunk("address", before, seen.size());
        int removed = repo.deleteAdressesExcept(seen);
        return new Outcome(fetched, changed.size() + removed, removed, null);
    }

    static List<ContexteRows.Adresse> toRows(List<LayerPage.Feature> features) {
        List<ContexteRows.Adresse> rows = new ArrayList<>(features.size());
        for (LayerPage.Feature f : features) {
            String id = f.text("IDPADR");
            String adresse = f.text("ADRESSE");
            if (id == null || adresse == null) {
                continue;
            }
            rows.add(new ContexteRows.Adresse(id, f.number("EGID"), adresse, f.integer("NO_POSTAL"),
                    f.text("NOM_NPA"), "Oui".equalsIgnoreCase(f.text("POSTALE"))));
        }
        return rows;
    }
}
