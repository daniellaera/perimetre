package ch.perimetre.parcelle;

import java.util.Collection;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Links freshly imported dossiers to their parcels (its own transaction, after the dossier import's). */
@Service
public class DossierLinker {

    private final ParcelleRepository parcelles;
    private final TransactionTemplate tx;

    DossierLinker(ParcelleRepository parcelles, TransactionTemplate tx) {
        this.parcelles = parcelles;
        this.tx = tx;
    }

    /** Recomputes the parcel links of {@code idDossiers}; returns how many were relinked. */
    public int link(Collection<String> idDossiers) {
        Integer n = tx.execute(status -> parcelles.relinkDossiers(idDossiers));
        return n == null ? 0 : n;
    }
}
