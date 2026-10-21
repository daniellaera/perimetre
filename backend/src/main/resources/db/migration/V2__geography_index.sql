-- Radius queries use ST_DWithin on geom::geography (metres), which the geometry GIST index can't serve.
CREATE INDEX dossier_geog_gist ON dossier USING GIST ((geom::geography));
