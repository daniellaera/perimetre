package ch.perimetre.notification;

import static java.util.Map.entry;

import java.util.Locale;
import java.util.Map;

import ch.perimetre.dossier.Groupe;

/** Push wording (French). Never includes personal names: only SITG's work description. */
final class PushMessages {

    static final String DEFAULT_OPERATION_LABEL = "Demande";
    static final String SUMMARY_KIND = "SUMMARY";

    private static final int TITLE_DESCRIPTION_CHARS = 40;
    private static final int BODY_DESCRIPTION_CHARS = 90;

    /** Short labels for type_operation; same vocabulary as the app (CLAUDE.md). */
    private static final Map<String, String> OPERATION_LABELS = Map.ofEntries(
            entry("VIL", "Nouvelle villa"),
            entry("NOU", "Construction nouvelle"),
            entry("AGR", "Agrandissement"),
            entry("AGV", "Agrandissement de villa"),
            entry("REN", "Rénovation"),
            entry("PI", "Petite construction"),
            entry("EQU", "Aménagement public"),
            entry("AFF", "Changement d'affectation"));

    private static final Map<Groupe, String> EVENT_TEXTS = Map.of(
            Groupe.DEPOSE, "Nouvelle demande déposée — délai d'opposition en cours",
            Groupe.AUTORISE, "Autorisation délivrée",
            Groupe.CHANTIER, "Les travaux commencent",
            Groupe.RECOURS, "Un recours a été déposé",
            Groupe.REFUSE, "Le projet ne se fera pas");

    private PushMessages() {
    }

    static String operationLabel(String typeDossier, String typeOperation) {
        if ("M".equals(typeDossier) || "MPA".equals(typeDossier)) {
            return "Démolition";
        }
        return typeOperation == null ? DEFAULT_OPERATION_LABEL
                : OPERATION_LABELS.getOrDefault(typeOperation.trim(), DEFAULT_OPERATION_LABEL);
    }

    static String eventText(Groupe groupe) {
        String text = EVENT_TEXTS.get(groupe);
        if (text == null) {
            throw new IllegalArgumentException("No event text for groupe " + groupe);
        }
        return text;
    }

    /** title "{label} à {distance} m"; body "{event text}. {description}"; data for the app. */
    static PushMessage forEvent(PendingNotification n) {
        String description = cleanDescription(n.description());
        String label = operationLabel(n.typeDossier(), n.typeOperation());
        if (label.equals(DEFAULT_OPERATION_LABEL) && description != null) {
            label = truncate(description, TITLE_DESCRIPTION_CHARS);
        }
        String title = label + " à " + Math.round(n.distanceM()) + " m";
        String body = eventText(n.newGroupe()) + "."
                + (description == null ? "" : " " + truncate(description, BODY_DESCRIPTION_CHARS));
        return new PushMessage(title, body, Map.of(
                "id_dossier", n.idDossier(),
                "kind", n.kind().name(),
                "new_groupe", n.newGroupe().name()));
    }

    static PushMessage summary(int count) {
        return new PushMessage("Périmètre", count + " nouveaux changements autour de chez vous", Map.of(
                "kind", SUMMARY_KIND,
                "count", Integer.toString(count)));
    }

    /** Trimmed, first letter capitalised; null when blank. SITG descriptions often start lower-case. */
    static String cleanDescription(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.strip();
        return text.substring(0, 1).toUpperCase(Locale.ROOT) + text.substring(1);
    }

    /** At most {@code max} characters of {@code text}, plus "…" when cut. */
    static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max).stripTrailing() + "…";
    }
}
