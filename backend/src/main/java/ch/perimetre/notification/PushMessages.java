package ch.perimetre.notification;

import static java.util.Map.entry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import ch.perimetre.dossier.Groupe;

/** Push wording (French). Never includes personal names: only SITG's work description. */
final class PushMessages {

    static final String DEFAULT_OPERATION_LABEL = "Demande";
    static final String SUMMARY_KIND = "SUMMARY";

    private static final int TITLE_DESCRIPTION_CHARS = 40;
    private static final int BODY_DESCRIPTION_CHARS = 90;
    private static final int PLACE_NAME_CHARS = 30;
    private static final int TITLE_PLACES_CHARS = 40;

    /** "… 1206 Genève" at the end of a geo.admin.ch address: postcode and locality. */
    private static final Pattern POSTCODE_AND_LOCALITY = Pattern.compile("\\s+\\d{4}\\s+\\S.*$");

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

    /** A single matching subscription (e.g. the admin test push). */
    static PushMessage forEvent(PendingNotification n) {
        return forEvent(List.of(n));
    }

    /**
     * One event, matched by one or more subscriptions of the same device, nearest first. Title
     * "{label} à {distance} m", distance from the nearest, followed by " · {places}" when the matched
     * subscriptions are to be named; body "{event text}. {description}"; data for the app.
     */
    static PushMessage forEvent(List<PendingNotification> matches) {
        PendingNotification n = matches.getFirst();
        String description = cleanDescription(n.description());
        String label = operationLabel(n.typeDossier(), n.typeOperation());
        if (label.equals(DEFAULT_OPERATION_LABEL) && description != null) {
            label = truncate(description, TITLE_DESCRIPTION_CHARS);
        }
        String places = matches.stream().map(PushMessages::placeName).filter(Objects::nonNull).distinct()
                .collect(Collectors.joining(", "));
        String title = label + " à " + Math.round(n.distanceM()) + " m"
                + (places.isEmpty() ? "" : " · " + truncate(places, TITLE_PLACES_CHARS));
        String body = eventText(n.newGroupe()) + "."
                + (description == null ? "" : " " + truncate(description, BODY_DESCRIPTION_CHARS));
        return new PushMessage(title, body, Map.of(
                "id_dossier", n.idDossier(),
                "kind", n.kind().name(),
                "new_groupe", n.newGroupe().name(),
                "subscription_id", n.subscriptionId().toString()));
    }

    /**
     * How a matched subscription is named in a push: its label; else, when the device has several
     * subscriptions, its address without postcode and locality; else nothing (a single unlabelled
     * subscription reads as before: the single-subscription app's pushes are unchanged).
     */
    static String placeName(PendingNotification n) {
        if (n.label() != null && !n.label().isBlank()) {
            return truncate(n.label().strip(), PLACE_NAME_CHARS);
        }
        if (n.deviceSubscriptions() > 1 && n.address() != null && !n.address().isBlank()) {
            return truncate(POSTCODE_AND_LOCALITY.matcher(n.address().strip()).replaceFirst(""), PLACE_NAME_CHARS);
        }
        return null;
    }

    static PushMessage summary(int count) {
        return new PushMessage("Périmètre", count + " nouveaux changements autour de chez vous", Map.of(
                "kind", SUMMARY_KIND,
                "count", Integer.toString(count)));
    }

    /**
     * One push for many events (each a list of matches, nearest first). Named places get a count each:
     * "7 nouveaux changements (Domicile : 5, Travail : 2)", an event counting once per place it matched.
     */
    static PushMessage summary(List<List<PendingNotification>> events) {
        Map<String, Integer> perPlace = new LinkedHashMap<>();
        for (List<PendingNotification> matches : events) {
            matches.stream().map(PushMessages::placeName).filter(Objects::nonNull).distinct()
                    .forEach(place -> perPlace.merge(place, 1, Integer::sum));
        }
        if (perPlace.isEmpty()) {
            return summary(events.size());
        }
        String places = perPlace.entrySet().stream().map(e -> e.getKey() + " : " + e.getValue())
                .collect(Collectors.joining(", "));
        return new PushMessage("Périmètre", events.size() + " nouveaux changements (" + places + ")", Map.of(
                "kind", SUMMARY_KIND,
                "count", Integer.toString(events.size())));
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
