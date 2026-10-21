CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE dossier (
    id_dossier     TEXT PRIMARY KEY,
    type_dossier   TEXT NOT NULL,
    type_operation TEXT,
    operation      TEXT,
    description    TEXT,
    statut_sitg    TEXT,
    groupe         TEXT NOT NULL,
    date_depot     DATE,
    date_maj_sitg  TIMESTAMPTZ NOT NULL,
    lien_sad       TEXT,
    geom           geometry(Point, 4326) NOT NULL,
    flags          TEXT[] NOT NULL DEFAULT '{}',
    first_seen     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX dossier_geom_idx ON dossier USING GIST (geom);
CREATE INDEX dossier_date_maj_sitg_idx ON dossier (date_maj_sitg);

CREATE TABLE sync_state (
    source   TEXT PRIMARY KEY,
    last_run TIMESTAMPTZ NOT NULL
);

INSERT INTO sync_state (source, last_run) VALUES ('SIT_AUTOR_DOSSIER', '2025-01-01T00:00:00Z');
