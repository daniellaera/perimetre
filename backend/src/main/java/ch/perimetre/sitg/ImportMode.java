package ch.perimetre.sitg;

import java.util.Locale;

public enum ImportMode {
    /** Dossiers with {@code date_maj_2 >= last_run - 3 days}. */
    INCREMENTAL,
    /** Every dossier modified since 2025-01-01, to catch statut changes that did not move date_maj_2. */
    FULL;

    static ImportMode parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode must be 'incremental' or 'full', got '" + value + "'");
        }
    }
}
