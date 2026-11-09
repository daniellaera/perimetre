-- Cadastral parcels (SITG layer CAD_PARCELLE_MENSU, "parcelle en vigueur", open data) and their link to
-- dossiers. Open attributes only: never owner names or any non-open registry data (TYPE_PROPRI and
-- PROPRI_PUBLIQUE of the source are deliberately not imported).
CREATE TABLE parcelle (
    id_parcelle TEXT PRIMARY KEY,               -- SITG IDEDDP "<no_comm>:<no_parcelle>" (business key)
    egrid       TEXT,
    commune     TEXT NOT NULL,
    no_comm     INT NOT NULL,
    no_parcelle INT NOT NULL,
    surface_m2  INT,
    date_mut    DATE,                           -- DATEDT: last mutation, the incremental cursor
    lien_rf     TEXT,                           -- official ge.ch extrait foncier link, as published by SITG
    geom        geometry(MultiPolygon, 4326) NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX parcelle_geom_idx ON parcelle USING GIST (geom);
CREATE INDEX parcelle_date_mut_idx ON parcelle (date_mut);

-- A dossier's point falls in 0 (public domain without parcel), 1 or, on a boundary, 2+ parcels.
CREATE TABLE dossier_parcelle (
    id_dossier  TEXT NOT NULL REFERENCES dossier (id_dossier) ON DELETE CASCADE,
    id_parcelle TEXT NOT NULL REFERENCES parcelle (id_parcelle) ON DELETE CASCADE,
    PRIMARY KEY (id_dossier, id_parcelle)
);

CREATE INDEX dossier_parcelle_parcelle_idx ON dossier_parcelle (id_parcelle);

-- bootstrapped = false makes the first run a full import whatever its mode.
INSERT INTO sync_state (source, last_run) VALUES ('CAD_PARCELLE_MENSU', '2025-01-01T00:00:00Z');
