package ch.perimetre.sitg;

import static java.util.Map.entry;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import ch.perimetre.dossier.Groupe;

final class SitgMapping {

    private static final Map<String, Groupe> GROUPE_BY_STATUT = Map.ofEntries(
            entry("ENREGISTREMENT", Groupe.DEPOSE),
            entry("ENREGISTRE", Groupe.DEPOSE),
            entry("INSTRUCTION", Groupe.DEPOSE),
            entry("ACCEPTE", Groupe.AUTORISE),
            entry("DECIDE", Groupe.AUTORISE),
            entry("CHANTIER", Groupe.CHANTIER),
            entry("RECOURS", Groupe.RECOURS),
            entry("REFUSE", Groupe.REFUSE),
            entry("REFUS ENTREE", Groupe.REFUSE),
            entry("ABANDONNE", Groupe.REFUSE),
            entry("CADUQUE", Groupe.REFUSE),
            entry("AUTORISATION ANNULEE", Groupe.REFUSE),
            entry("TERMINE", Groupe.TERMINE),
            entry("ARCHIVE", Groupe.TERMINE),
            entry("ARCHANT", Groupe.TERMINE),
            entry("CLOTURE ADMIN", Groupe.TERMINE));

    // Ordered: flags are emitted in this order.
    private static final List<Map.Entry<String, List<String>>> FLAG_KEYWORDS = List.of(
            entry("arbres", List.of("abattage", "élagage")),
            entry("logements", List.of("logement")),
            entry("parking", List.of("parking", "stationnement")),
            entry("piscine", List.of("piscine")),
            entry("antenne", List.of("télécommunication mobile", "antenne")),
            entry("surelevation", List.of("surélévation")),
            entry("plq", List.of("plq")));

    private SitgMapping() {
    }

    static Groupe groupe(String statut) {
        if (statut == null) {
            return Groupe.IGNORE;
        }
        return GROUPE_BY_STATUT.getOrDefault(statut.trim(), Groupe.IGNORE);
    }

    static List<String> flags(String description) {
        if (description == null) {
            return List.of();
        }
        String text = Normalizer.normalize(description, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
        return FLAG_KEYWORDS.stream()
                .filter(flag -> flag.getValue().stream().anyMatch(text::contains))
                .map(Map.Entry::getKey)
                .toList();
    }
}
