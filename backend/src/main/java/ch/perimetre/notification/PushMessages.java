package ch.perimetre.notification;

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

    static final String SUMMARY_KIND = "SUMMARY";
    static final String TEST_KIND = "TEST";

    private static final int BODY_DESCRIPTION_CHARS = 90;
    private static final int PLACE_NAME_CHARS = 30;
    private static final int TITLE_PLACES_CHARS = 40;

    /** "… 1206 Genève" at the end of a geo.admin.ch address: postcode and locality. */
    private static final Pattern POSTCODE_AND_LOCALITY = Pattern.compile("\\s+\\d{4}\\s+\\S.*$");

    private static final Map<Groupe, String> EVENT_TEXTS = Map.of(
            Groupe.DEPOSE, "Nouvelle demande",
            Groupe.AUTORISE, "Autorisation délivrée",
            Groupe.CHANTIER, "Les travaux commencent",
            Groupe.RECOURS, "Un recours a été déposé",
            Groupe.REFUSE, "Le projet ne se fera pas");

    private PushMessages() {
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
     * "{distance} · {places}" (see {@link #title}), body "{change label} — {description}" (see {@link #body}),
     * data for the app (unchanged: a tap opens the dossier).
     */
    static PushMessage forEvent(List<PendingNotification> matches) {
        PendingNotification n = matches.getFirst();
        String places = places(matches);
        String address = matches.stream().map(PushMessages::street).filter(Objects::nonNull).findFirst().orElse(null);
        return new PushMessage(title(n.distanceM(), places, address), body(n.newGroupe(), n.description()), Map.of(
                "id_dossier", n.idDossier(),
                "kind", n.kind().name(),
                "new_groupe", n.newGroupe().name(),
                "subscription_id", n.subscriptionId().toString()));
    }

    /**
     * What a dossier push shows first, which is all a phone's lock screen keeps of a long title: the distance,
     * then the matched perimeters ("104 m · Peillonnex"); with none to name, "104 m de {street address}", else
     * just the distance.
     */
    static String title(double distanceM, String places, String address) {
        String distance = distance(distanceM);
        if (places != null && !places.isBlank()) {
            return distance + " · " + truncate(places, TITLE_PLACES_CHARS);
        }
        if (address != null && !address.isBlank()) {
            return distance + " de " + truncate(address, PLACE_NAME_CHARS);
        }
        return distance;
    }

    /** "{change label} — {description}", the description capped at 90 characters; the label alone without one. */
    static String body(Groupe newGroupe, String rawDescription) {
        String description = cleanDescription(rawDescription);
        return eventText(newGroupe) + (description == null ? "" : " — " + truncate(description, BODY_DESCRIPTION_CHARS));
    }

    /** Whole metres under a kilometre ("104 m"), then kilometres with a decimal comma ("1,2 km"). */
    static String distance(double metres) {
        long rounded = Math.round(metres);
        if (rounded < 1000) {
            return rounded + " m";
        }
        return String.format(Locale.ROOT, "%.1f km", rounded / 1000.0).replace('.', ',');
    }

    /** The matched perimeters' names, nearest first, comma-separated; empty when none is to be named. */
    static String places(List<PendingNotification> matches) {
        return matches.stream().map(PushMessages::placeName).filter(Objects::nonNull).distinct()
                .collect(Collectors.joining(", "));
    }

    /** The subscription's address without postcode and locality; null when it has none. */
    static String street(PendingNotification n) {
        if (n.address() == null || n.address().isBlank()) {
            return null;
        }
        return POSTCODE_AND_LOCALITY.matcher(n.address().strip()).replaceFirst("");
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

    /** Sample dossier of the test push: realistic, so the phone shows what a real alert looks like. */
    static final double TEST_DISTANCE_M = 104;
    static final String TEST_DESCRIPTION = "Modification d'une installation de télécommunication mobile existante";

    /**
     * POST /devices/{id}/test-push: the real title and body format applied to a sample dossier near the
     * device's oldest perimeter ({@code perimeter}: its label, address and the device's subscription count; null
     * without one). Names no real dossier: the data is {@code kind=TEST}, so the app opens Mes périmètres on a tap.
     */
    static PushMessage test(PendingNotification perimeter) {
        String places = perimeter == null ? "" : places(List.of(perimeter));
        String address = perimeter == null ? null : street(perimeter);
        return new PushMessage(title(TEST_DISTANCE_M, places, address), body(Groupe.DEPOSE, TEST_DESCRIPTION),
                Map.of("kind", TEST_KIND));
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
