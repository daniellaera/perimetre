package ch.perimetre.sitg;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import ch.perimetre.dossier.Dossier;
import ch.perimetre.dossier.Groupe;
import ch.perimetre.event.EventKind;
import ch.perimetre.event.NewEvent;

/** Compares incoming dossiers with their stored groupe and decides which events to emit. */
final class ChangeDetector {

    private ChangeDetector() {
    }

    /**
     * @param existing     stored groupe by id_dossier, for the incoming dossiers that already exist
     * @param incoming     dossiers about to be upserted (one per id_dossier)
     * @param bootstrapped false on the very first import (or after a DB reset): emit nothing
     */
    static List<NewEvent> detect(Map<String, Groupe> existing, Collection<Dossier> incoming, boolean bootstrapped) {
        if (!bootstrapped) {
            return List.of();
        }
        List<NewEvent> events = new ArrayList<>();
        for (Dossier d : incoming) {
            Groupe before = existing.get(d.idDossier());
            Groupe after = d.groupe();
            if (before == null) {
                if (after == Groupe.DEPOSE) {
                    events.add(new NewEvent(d.idDossier(), EventKind.NEW, null, after));
                }
            } else if (before != after && after != Groupe.IGNORE && after != Groupe.TERMINE) {
                events.add(new NewEvent(d.idDossier(), EventKind.GROUPE_CHANGE, before, after));
            }
        }
        return events;
    }
}
