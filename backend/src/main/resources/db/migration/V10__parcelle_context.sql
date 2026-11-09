-- Open context of a parcel, all from SITG open data (access class A "Accès libre", see CLAUDE.md "Parcel context"):
--   parcelle.type_parcelle  TYPE_PROPRI of CAD_PARCELLE_MENSU: an 8-value category (privé, DP communal, chemin
--                           vicinal...), NOT a name. PROPRI_PUBLIQUE (names the public owner) is never imported.
--   parcelle.plan_rf        PLAN_RF, the land-registry plan number
--   parcelle.lieu_dit       local name (CAD_NOMS_LOCAUX) the parcel lies in
--   batiment                CAD_BATIMENT_HORSOL, with the addresses of CAD_ADRESSE (by EGID)
--   zone_affectation        RDPPF_ZONES_PRIM + RDPPF_ZONES_DEV (land-use zones), RDPPF layers published openly
-- Never owners, never anything from the non-open registry.
ALTER TABLE parcelle
    ADD COLUMN type_parcelle TEXT,
    ADD COLUMN plan_rf       TEXT,
    ADD COLUMN lieu_dit      TEXT;

CREATE TABLE batiment (
    egid               BIGINT PRIMARY KEY,           -- federal building id, unique and never null in the layer
    commune            TEXT,
    no_batiment        TEXT,                         -- the building number shown to people
    destination        TEXT,
    nomenclature       TEXT,
    surface_m2         INT,                          -- footprint, as published
    annee_construction INT,
    niveaux_horsol     INT,
    date_mut           DATE,                         -- DATEDT: last mutation, the incremental cursor
    egrid_liste        TEXT[] NOT NULL DEFAULT '{}', -- the parcels the building stands on (official)
    geom               geometry(MultiPolygon, 4326) NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX batiment_geom_idx ON batiment USING GIST (geom);
CREATE INDEX batiment_egrid_idx ON batiment USING GIN (egrid_liste);
CREATE INDEX batiment_date_mut_idx ON batiment (date_mut);

CREATE TABLE parcelle_batiment (
    id_parcelle             TEXT NOT NULL REFERENCES parcelle (id_parcelle) ON DELETE CASCADE,
    egid                    BIGINT NOT NULL REFERENCES batiment (egid) ON DELETE CASCADE,
    surface_sur_parcelle_m2 INT,                     -- footprint inside this parcel; null when it cannot be told
    PRIMARY KEY (id_parcelle, egid)
);
CREATE INDEX parcelle_batiment_egid_idx ON parcelle_batiment (egid);

CREATE TABLE adresse (
    idpadr    TEXT PRIMARY KEY,
    egid      BIGINT,
    adresse   TEXT NOT NULL,                         -- "Rue de Graman 99A"
    no_postal INT,
    npa       TEXT,
    postale   BOOLEAN
);
CREATE INDEX adresse_egid_idx ON adresse (egid);

CREATE TABLE zone_affectation (
    id          BIGSERIAL PRIMARY KEY,
    source      TEXT NOT NULL,                       -- ZONE (RDPPF_ZONES_PRIM) | ZONE_DEVELOPPEMENT (RDPPF_ZONES_DEV)
    code        TEXT,                                -- ABRV_ZONE
    nom         TEXT,                                -- NOM_ZONE
    description TEXT,                                -- DESC_ZONE
    commune     TEXT,
    statut      TEXT,                                -- STATUT_JUR
    geom        geometry(MultiPolygon, 4326) NOT NULL
);
CREATE INDEX zone_affectation_geom_idx ON zone_affectation USING GIST (geom);

CREATE TABLE parcelle_zone (
    id_parcelle TEXT NOT NULL REFERENCES parcelle (id_parcelle) ON DELETE CASCADE,
    id_zone     BIGINT NOT NULL REFERENCES zone_affectation (id) ON DELETE CASCADE,
    surface_m2  INT NOT NULL,
    part_pct    INT NOT NULL,
    PRIMARY KEY (id_parcelle, id_zone)
);
CREATE INDEX parcelle_zone_zone_idx ON parcelle_zone (id_zone);

CREATE TABLE lieu_dit (
    id    BIGSERIAL PRIMARY KEY,
    texte TEXT NOT NULL,
    geom  geometry(MultiPolygon, 4326) NOT NULL
);
CREATE INDEX lieu_dit_geom_idx ON lieu_dit USING GIST (geom);

-- The parcel columns above are filled by the next full parcel import (the first run after this migration
-- refetches everything: see ParcelleImporter, which marks the parcel source as not bootstrapped).
UPDATE sync_state SET bootstrapped = false WHERE source = 'CAD_PARCELLE_MENSU';

INSERT INTO sync_state (source, last_run) VALUES
    ('CAD_BATIMENT_HORSOL', '2025-01-01T00:00:00Z'),
    ('CAD_ADRESSE', '2025-01-01T00:00:00Z'),
    ('RDPPF_ZONES', '2025-01-01T00:00:00Z'),
    ('CAD_NOMS_LOCAUX', '2025-01-01T00:00:00Z');
