package ch.perimetre.subscription;

import java.util.List;
import java.util.Set;

import ch.perimetre.dossier.Groupe;

/** The groupes a subscription can be notified about, and the normalisation of a requested list. */
public final class Categories {

    public static final List<Groupe> NOTIFIABLE =
            List.of(Groupe.DEPOSE, Groupe.AUTORISE, Groupe.CHANTIER, Groupe.RECOURS, Groupe.REFUSE);

    static final String INVALID_MESSAGE = "categories must be among DEPOSE, AUTORISE, CHANTIER, RECOURS, REFUSE";

    private Categories() {
    }

    /** Requested categories, de-duplicated in canonical order, or all of them when absent. */
    static List<Groupe> orDefault(List<Groupe> categories) {
        if (categories == null) {
            return NOTIFIABLE;
        }
        Set<Groupe> requested = Set.copyOf(categories);
        return NOTIFIABLE.stream().filter(requested::contains).toList();
    }

    static boolean isValid(List<Groupe> categories) {
        return categories == null || NOTIFIABLE.containsAll(categories);
    }

    static String[] toSql(List<Groupe> categories) {
        return orDefault(categories).stream().map(Groupe::name).toArray(String[]::new);
    }
}
