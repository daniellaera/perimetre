package ch.perimetre.event;

import ch.perimetre.dossier.Groupe;

/** An event to insert; {@code oldGroupe} is null for {@link EventKind#NEW}. */
public record NewEvent(String idDossier, EventKind kind, Groupe oldGroupe, Groupe newGroupe) {
}
