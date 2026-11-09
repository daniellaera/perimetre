package ch.perimetre.parcelle;

import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The open context of parcels: buildings (+ addresses), land-use zones, local names. Importers write the
 * source tables; the derived tables ({@code parcelle_batiment}, {@code parcelle_zone}, {@code parcelle.lieu_dit})
 * are recomputed for the parcels, buildings or layers that changed. Callers run inside a transaction.
 */
@Repository
public class ContexteRepository {

    private static final int BATCH_SIZE = 500;
    /** A zone counts for a parcel when it covers at least 1 m² and 1% of it (smaller overlaps are edge slivers). */
    static final int ZONE_MIN_M2 = 1;
    static final double ZONE_MIN_SHARE = 0.01;
    /** The API shows at most this many buildings / zones per parcel (the full count stays in nb_batiments). */
    public static final int MAX_BATIMENTS = 30;
    public static final int MAX_RESTRICTIONS = 10;

    private static final String GEOM = "ST_Multi(ST_CollectionExtract(ST_MakeValid("
            + "ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)), 3))";

    private static final String UPSERT_BATIMENT = """
            INSERT INTO batiment (egid, commune, no_batiment, destination, nomenclature, surface_m2,
                                  annee_construction, niveaux_horsol, date_mut, egrid_liste, geom)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, COALESCE(string_to_array(?, ','), '{}'), %s)
            ON CONFLICT (egid) DO UPDATE SET
                commune = EXCLUDED.commune, no_batiment = EXCLUDED.no_batiment,
                destination = EXCLUDED.destination, nomenclature = EXCLUDED.nomenclature,
                surface_m2 = EXCLUDED.surface_m2, annee_construction = EXCLUDED.annee_construction,
                niveaux_horsol = EXCLUDED.niveaux_horsol, date_mut = EXCLUDED.date_mut,
                egrid_liste = EXCLUDED.egrid_liste, geom = EXCLUDED.geom, updated_at = now()
            WHERE (batiment.commune, batiment.no_batiment, batiment.destination, batiment.nomenclature,
                   batiment.surface_m2, batiment.annee_construction, batiment.niveaux_horsol, batiment.date_mut,
                   batiment.egrid_liste)
                  IS DISTINCT FROM (EXCLUDED.commune, EXCLUDED.no_batiment, EXCLUDED.destination,
                                    EXCLUDED.nomenclature, EXCLUDED.surface_m2, EXCLUDED.annee_construction,
                                    EXCLUDED.niveaux_horsol, EXCLUDED.date_mut, EXCLUDED.egrid_liste)
               OR NOT ST_OrderingEquals(batiment.geom, EXCLUDED.geom)
            """.formatted(GEOM);

    private static final String UPSERT_ADRESSE = """
            INSERT INTO adresse (idpadr, egid, adresse, no_postal, npa, postale) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (idpadr) DO UPDATE SET egid = EXCLUDED.egid, adresse = EXCLUDED.adresse,
                no_postal = EXCLUDED.no_postal, npa = EXCLUDED.npa, postale = EXCLUDED.postale
            WHERE (adresse.egid, adresse.adresse, adresse.no_postal, adresse.npa, adresse.postale)
                  IS DISTINCT FROM (EXCLUDED.egid, EXCLUDED.adresse, EXCLUDED.no_postal, EXCLUDED.npa,
                                    EXCLUDED.postale)
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    ContexteRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ---- buildings -------------------------------------------------------------------------------------

    /** Upserts {@code rows}; returns the egids inserted or really changed. */
    List<Long> upsertBatiments(List<ContexteRows.Batiment> rows) {
        int[][] counts = jdbcTemplate.batchUpdate(UPSERT_BATIMENT, rows, BATCH_SIZE, (ps, r) -> {
            ps.setLong(1, r.egid());
            ps.setString(2, r.commune());
            ps.setString(3, r.noBatiment());
            ps.setString(4, r.destination());
            ps.setString(5, r.nomenclature());
            ps.setObject(6, r.surfaceM2());
            ps.setObject(7, r.anneeConstruction());
            ps.setObject(8, r.niveauxHorsol());
            ps.setObject(9, r.dateMut());
            ps.setString(10, r.egridListe());
            ps.setString(11, r.geometryJson());
        });
        List<Long> changed = new ArrayList<>();
        int i = 0;
        for (int[] batch : counts) {
            for (int c : batch) {
                if (c > 0 || c == Statement.SUCCESS_NO_INFO) {
                    changed.add(rows.get(i).egid());
                }
                i++;
            }
        }
        return changed;
    }

    long countBatiments() {
        return jdbc.sql("SELECT count(*) FROM batiment").query(Long.class).single();
    }

    int deleteBatimentsExcept(Collection<Long> seen) {
        return jdbc.sql("DELETE FROM batiment WHERE NOT (egid = ANY(CAST(:seen AS bigint[])))")
                .param("seen", seen.toArray(Long[]::new)).update();
    }

    private static final String LINK_BATIMENT = """
            INSERT INTO parcelle_batiment (id_parcelle, egid, surface_sur_parcelle_m2)
            SELECT p.id_parcelle, b.egid,
                   CASE WHEN ST_Intersects(b.geom, p.geom)
                        THEN NULLIF(round(ST_Area(ST_Intersection(b.geom, ST_MakeValid(p.geom))::geography))::int, 0) END
            FROM batiment b JOIN parcelle p ON b.egrid_liste @> ARRAY[p.egrid]
            """;

    /** Relinks the given buildings (all of them when {@code egids} is null) to their parcels. */
    int relinkBatiments(Collection<Long> egids) {
        if (egids == null) {
            jdbc.sql("DELETE FROM parcelle_batiment").update();
            return jdbc.sql(LINK_BATIMENT).update();
        }
        if (egids.isEmpty()) {
            return 0;
        }
        Long[] ids = egids.toArray(Long[]::new);
        jdbc.sql("DELETE FROM parcelle_batiment WHERE egid = ANY(CAST(:ids AS bigint[]))").param("ids", ids).update();
        return jdbc.sql(LINK_BATIMENT + " WHERE b.egid = ANY(CAST(:ids AS bigint[]))").param("ids", ids).update();
    }

    // ---- addresses -------------------------------------------------------------------------------------

    List<String> upsertAdresses(List<ContexteRows.Adresse> rows) {
        int[][] counts = jdbcTemplate.batchUpdate(UPSERT_ADRESSE, rows, BATCH_SIZE, (ps, r) -> {
            ps.setString(1, r.idpadr());
            ps.setObject(2, r.egid());
            ps.setString(3, r.adresse());
            ps.setObject(4, r.noPostal());
            ps.setString(5, r.npa());
            ps.setBoolean(6, r.postale());
        });
        List<String> changed = new ArrayList<>();
        int i = 0;
        for (int[] batch : counts) {
            for (int c : batch) {
                if (c > 0 || c == Statement.SUCCESS_NO_INFO) {
                    changed.add(rows.get(i).idpadr());
                }
                i++;
            }
        }
        return changed;
    }

    long countAdresses() {
        return jdbc.sql("SELECT count(*) FROM adresse").query(Long.class).single();
    }

    int deleteAdressesExcept(Collection<String> seen) {
        return jdbc.sql("DELETE FROM adresse WHERE NOT (idpadr = ANY(CAST(:seen AS text[])))")
                .param("seen", seen.toArray(String[]::new)).update();
    }

    // ---- zones and local names: small layers, replaced as a whole ---------------------------------------

    long countZones() {
        return jdbc.sql("SELECT count(*) FROM zone_affectation").query(Long.class).single();
    }

    /** Replaces every zone (their ids change, so {@code parcelle_zone} is rebuilt by {@link #relinkZones}). */
    void replaceZones(List<ContexteRows.Zone> rows) {
        jdbc.sql("DELETE FROM zone_affectation").update();
        jdbcTemplate.batchUpdate("INSERT INTO zone_affectation (source, code, nom, description, commune, statut, geom)"
                + " VALUES (?, ?, ?, ?, ?, ?, " + GEOM + ")", rows, BATCH_SIZE, (ps, r) -> {
                    ps.setString(1, r.source());
                    ps.setString(2, r.code());
                    ps.setString(3, r.nom());
                    ps.setString(4, r.description());
                    ps.setString(5, r.commune());
                    ps.setString(6, r.statut());
                    ps.setString(7, r.geometryJson());
                });
    }

    private static final String LINK_ZONE = """
            INSERT INTO parcelle_zone (id_parcelle, id_zone, surface_m2, part_pct)
            SELECT s.id_parcelle, s.id_zone, round(s.a)::int, least(100, round(100 * s.a / s.pa))::int
            FROM (SELECT p.id_parcelle, z.id AS id_zone,
                         ST_Area(ST_Intersection(v.g, z.geom)::geography) AS a,
                         ST_Area(v.g::geography) AS pa
                  FROM parcelle p CROSS JOIN LATERAL (SELECT ST_MakeValid(p.geom) AS g) v
                  JOIN zone_affectation z ON ST_Intersects(p.geom, z.geom)
                  %s) s
            WHERE s.pa > 0 AND s.a >= %d AND s.a >= %s * s.pa
            """;

    /** Recomputes the zones of the given parcels (all of them when {@code parcelIds} is null). */
    int relinkZones(Collection<String> parcelIds) {
        String sql = LINK_ZONE.formatted(parcelIds == null ? "" : "WHERE p.id_parcelle = ANY(CAST(:ids AS text[]))",
                ZONE_MIN_M2, ZONE_MIN_SHARE);
        if (parcelIds == null) {
            jdbc.sql("DELETE FROM parcelle_zone").update();
            return jdbc.sql(sql).update();
        }
        if (parcelIds.isEmpty()) {
            return 0;
        }
        String[] ids = parcelIds.toArray(String[]::new);
        jdbc.sql("DELETE FROM parcelle_zone WHERE id_parcelle = ANY(CAST(:ids AS text[]))").param("ids", ids).update();
        return jdbc.sql(sql).param("ids", ids).update();
    }

    long countLieuxDits() {
        return jdbc.sql("SELECT count(*) FROM lieu_dit").query(Long.class).single();
    }

    void replaceLieuxDits(List<ContexteRows.LieuDit> rows) {
        jdbc.sql("DELETE FROM lieu_dit").update();
        jdbcTemplate.batchUpdate("INSERT INTO lieu_dit (texte, geom) VALUES (?, " + GEOM + ")", rows, BATCH_SIZE,
                (ps, r) -> {
                    ps.setString(1, r.texte());
                    ps.setString(2, r.geometryJson());
                });
    }

    /** Sets {@code parcelle.lieu_dit} (the local name the parcel's inner point lies in) for the given parcels. */
    int relinkLieuxDits(Collection<String> parcelIds) {
        String sql = """
                UPDATE parcelle p SET lieu_dit = (
                    SELECT l.texte FROM lieu_dit l WHERE ST_Intersects(l.geom, ST_PointOnSurface(p.geom))
                    ORDER BY l.id LIMIT 1)
                """;
        if (parcelIds == null) {
            return jdbc.sql(sql + " WHERE true").update();
        }
        if (parcelIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql(sql + " WHERE p.id_parcelle = ANY(CAST(:ids AS text[]))")
                .param("ids", parcelIds.toArray(String[]::new)).update();
    }

    /** Everything derived from the parcels themselves, after a parcel import (changed parcels only). */
    public void relinkParcels(Collection<String> parcelIds) {
        if (parcelIds.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM parcelle_batiment WHERE id_parcelle = ANY(CAST(:ids AS text[]))")
                .param("ids", parcelIds.toArray(String[]::new)).update();
        jdbc.sql(LINK_BATIMENT + " WHERE p.id_parcelle = ANY(CAST(:ids AS text[]))")
                .param("ids", parcelIds.toArray(String[]::new)).update();
        relinkZones(parcelIds);
        relinkLieuxDits(parcelIds);
    }

    // ---- read model for the API -------------------------------------------------------------------------

    /** What a parcel carries beyond its own columns; absent parcels have no entry. */
    record Details(List<BatimentRow> batiments, int nbBatiments, List<RestrictionRow> restrictions) {
    }

    record BatimentRow(long egid, String numero, String destination, Integer surfaceM2, Integer surfaceSurParcelleM2,
            Integer anneeConstruction, List<String> adresses) {
    }

    record RestrictionRow(String type, String code, String nom, String description, String statut, int surfaceM2,
            int partPct) {
    }

    Map<String, Details> detailsOf(Collection<String> parcelIds) {
        Map<String, List<BatimentRow>> batiments = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, List<RestrictionRow>> restrictions = new LinkedHashMap<>();
        if (!parcelIds.isEmpty()) {
            String[] ids = parcelIds.toArray(String[]::new);
            jdbc.sql("""
                    SELECT pb.id_parcelle, b.egid, b.no_batiment, b.destination, b.surface_m2,
                           pb.surface_sur_parcelle_m2, b.annee_construction,
                           (SELECT array_agg(DISTINCT a.adresse ORDER BY a.adresse) FROM adresse a
                             WHERE a.egid = b.egid) AS adresses,
                           count(*) OVER (PARTITION BY pb.id_parcelle) AS total
                    FROM parcelle_batiment pb JOIN batiment b USING (egid)
                    WHERE pb.id_parcelle = ANY(CAST(:ids AS text[]))
                    ORDER BY pb.id_parcelle, pb.surface_sur_parcelle_m2 DESC NULLS LAST, b.egid
                    """).param("ids", ids).query(rs -> {
                String id = rs.getString("id_parcelle");
                counts.put(id, rs.getInt("total"));
                List<BatimentRow> list = batiments.computeIfAbsent(id, k -> new ArrayList<>());
                if (list.size() < MAX_BATIMENTS) {
                    java.sql.Array arr = rs.getArray("adresses");
                    list.add(new BatimentRow(rs.getLong("egid"), rs.getString("no_batiment"),
                            rs.getString("destination"), rs.getObject("surface_m2", Integer.class),
                            rs.getObject("surface_sur_parcelle_m2", Integer.class),
                            rs.getObject("annee_construction", Integer.class),
                            arr == null ? List.of() : List.of((String[]) arr.getArray())));
                }
            });
            jdbc.sql("""
                    SELECT pz.id_parcelle, z.source, z.code, z.nom, z.description, z.statut,
                           sum(pz.surface_m2)::int AS surface, least(100, sum(pz.part_pct))::int AS part
                    FROM parcelle_zone pz JOIN zone_affectation z ON z.id = pz.id_zone
                    WHERE pz.id_parcelle = ANY(CAST(:ids AS text[]))
                    GROUP BY pz.id_parcelle, z.source, z.code, z.nom, z.description, z.statut
                    ORDER BY pz.id_parcelle, sum(pz.surface_m2) DESC, z.source, z.code
                    """).param("ids", ids).query(rs -> {
                List<RestrictionRow> list = restrictions.computeIfAbsent(rs.getString("id_parcelle"),
                        k -> new ArrayList<>());
                if (list.size() < MAX_RESTRICTIONS) {
                    list.add(new RestrictionRow(rs.getString("source"), rs.getString("code"), rs.getString("nom"),
                            rs.getString("description"), rs.getString("statut"), rs.getInt("surface"),
                            rs.getInt("part")));
                }
            });
        }
        Map<String, Details> out = new LinkedHashMap<>();
        for (String id : parcelIds) {
            out.put(id, new Details(batiments.getOrDefault(id, List.of()), counts.getOrDefault(id, 0),
                    restrictions.getOrDefault(id, List.of())));
        }
        return out;
    }
}
