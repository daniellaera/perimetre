package ch.perimetre.event;

public enum EventKind {
    /** A dossier seen for the first time, in groupe DEPOSE. */
    NEW,
    /** A known dossier whose groupe changed to anything but IGNORE or TERMINE. */
    GROUPE_CHANGE
}
