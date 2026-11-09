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
 * Parcels and their link to dossiers ({@code dossier_parcelle}: the dossier's point in the parcel, a
 * ~5 cm tolerant ST_Intersects, computed at import time). The relink methods must run inside a transaction: they take a
 * transaction-scoped advisory lock so a dossier import and a parcel import never rewrite links at once.
 */
@Repository
public class ParcelleRepository {

    private static final int BATCH_SIZE = 500;
    private static final long LINK_LOCK = 7_770_001L;

    // Rewrites only a row that really differs (so the returned update count says "changed").
    private static final String UPSERT = """
            INSERT INTO parcelle (id_parcelle, egrid, commune, no_comm, no_parcelle, surface_m2, date_mut, lien_rf,
                                  type_parcelle, plan_rf, geom)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)))
            ON CONFLICT (id_parcelle) DO UPDATE SET
                egrid       = EXCLUDED.egrid,
                commune     = EXCLUDED.commune,
                no_comm     = EXCLUDED.no_comm,
                no_parcelle = EXCLUDED.no_parcelle,
                surface_m2  = EXCLUDED.surface_m2,
                date_mut    = EXCLUDED.date_mut,
                lien_rf     = EXCLUDED.lien_rf,
                type_parcelle = EXCLUDED.type_parcelle,
                plan_rf     = EXCLUDED.plan_rf,
                geom        = EXCLUDED.geom,
                updated_at  = now()
            WHERE (parcelle.egrid, parcelle.commune, parcelle.no_comm, parcelle.no_parcelle, parcelle.surface_m2,
                   parcelle.date_mut, parcelle.lien_rf, parcelle.type_parcelle, parcelle.plan_rf)
                  IS DISTINCT FROM (EXCLUDED.egrid, EXCLUDED.commune, EXCLUDED.no_comm, EXCLUDED.no_parcelle,
                                    EXCLUDED.surface_m2, EXCLUDED.date_mut, EXCLUDED.lien_rf,
                                    EXCLUDED.type_parcelle, EXCLUDED.plan_rf)
               OR NOT ST_OrderingEquals(parcelle.geom, EXCLUDED.geom)
            """;

    // "The dossier's point is in the parcel", with a ~5 cm tolerance (5e-7 degrees): the parcel outlines are
    // stored at 7 decimals (~1 cm) and a dossier point on a shared boundary would otherwise pick either side
    // arbitrarily. Such a dossier gets both parcels. ST_DWithin on geometry still uses parcelle_geom_idx.
    static final String IN_PARCEL = "ST_DWithin(p.geom, d.geom, 0.0000005)";

    // (Not a text block: it would strip the space before the join condition.)
    private static final String LINK_INSERT = "INSERT INTO dossier_parcelle (id_dossier, id_parcelle) "
            + "SELECT d.id_dossier, p.id_parcelle FROM dossier d JOIN parcelle p ON " + IN_PARCEL + " ";

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;
    private final ContexteRepository contexte;

    ParcelleRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate, ContexteRepository contexte) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.contexte = contexte;
    }

    /** Upserts {@code rows}; returns the ids of those inserted or really changed (unchanged rows are not touched). */
    List<String> upsert(List<ParcelleRow> rows) {
        int[][] counts = jdbcTemplate.batchUpdate(UPSERT, rows, BATCH_SIZE, (ps, r) -> {
            ps.setString(1, r.idParcelle());
            ps.setString(2, r.egrid());
            ps.setString(3, r.commune());
            ps.setInt(4, r.noComm());
            ps.setInt(5, r.noParcelle());
            ps.setObject(6, r.surfaceM2());
            ps.setObject(7, r.dateMut());
            ps.setString(8, r.lienRf());
            ps.setString(9, r.typeParcelle());
            ps.setString(10, r.planRf());
            ps.setString(11, r.geometryJson());
        });
        List<String> changed = new ArrayList<>();
        int i = 0;
        for (int[] batch : counts) {
            for (int c : batch) {
                if (c > 0 || c == Statement.SUCCESS_NO_INFO) {
                    changed.add(rows.get(i).idParcelle());
                }
                i++;
            }
        }
        return changed;
    }

    long count() {
        return jdbc.sql("SELECT count(*) FROM parcelle").query(Long.class).single();
    }

    /** Deletes the parcels not in {@code seen} (full import: gone from the source). Their links cascade. */
    int deleteExcept(Collection<String> seen) {
        return jdbc.sql("DELETE FROM parcelle WHERE NOT (id_parcelle = ANY(CAST(:seen AS text[])))")
                .param("seen", seen.toArray(String[]::new))
                .update();
    }

    /** Recomputes every link from scratch. Returns the number of links. */
    int relinkAll() {
        lock();
        jdbc.sql("DELETE FROM dossier_parcelle").update();
        return jdbc.sql(LINK_INSERT + " ON CONFLICT DO NOTHING").update();
    }

    /**
     * Recomputes the links of the dossiers a change of {@code parcelIds} can affect (those linked to them
     * before, and those whose point now falls in them), plus any dossier without a link that now lies in a
     * parcel (a self-heal for a failed link step). Returns the number of dossiers relinked.
     */
    int relinkAffectedBy(Collection<String> parcelIds) {
        lock();
        String[] ids = parcelIds.toArray(String[]::new);
        List<String> affected = jdbc.sql(
                "SELECT id_dossier FROM dossier_parcelle WHERE id_parcelle = ANY(CAST(:ids AS text[])) "
                + "UNION SELECT d.id_dossier FROM dossier d JOIN parcelle p ON " + IN_PARCEL
                + " WHERE p.id_parcelle = ANY(CAST(:ids AS text[]))").param("ids", ids).query(String.class).list();
        int relinked = relink(affected);
        jdbc.sql(LINK_INSERT + " WHERE NOT EXISTS (SELECT 1 FROM dossier_parcelle x WHERE x.id_dossier = d.id_dossier)"
                + " ON CONFLICT DO NOTHING").update();
        return relinked;
    }

    /** Recomputes the links of {@code dossierIds} (after an import: new, moved or re-fetched dossiers). */
    int relinkDossiers(Collection<String> dossierIds) {
        lock();
        return relink(dossierIds);
    }

    private int relink(Collection<String> dossierIds) {
        if (dossierIds.isEmpty()) {
            return 0;
        }
        String[] ids = dossierIds.toArray(String[]::new);
        jdbc.sql("DELETE FROM dossier_parcelle WHERE id_dossier = ANY(CAST(:ids AS text[]))")
                .param("ids", ids).update();
        jdbc.sql(LINK_INSERT + " WHERE d.id_dossier = ANY(CAST(:ids AS text[])) ON CONFLICT DO NOTHING")
                .param("ids", ids).update();
        return ids.length;
    }

    private void lock() {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", LINK_LOCK).query().singleValue();
    }

    /** Parcels of each dossier, ordered by commune number then parcel number; dossiers without any are absent. */
    public Map<String, List<ParcelleView>> findByDossiers(Collection<String> dossierIds) {
        Map<String, List<ParcelleView>> byDossier = new LinkedHashMap<>();
        if (dossierIds.isEmpty()) {
            return byDossier;
        }
        record Base(String idDossier, String idParcelle, String egrid, String commune, int numero, Integer surface,
                String lienRf, String type, String planRf, String lieuDit) {
        }
        List<Base> bases = new ArrayList<>();
        jdbc.sql("""
                SELECT dp.id_dossier, p.id_parcelle, p.egrid, p.commune, p.no_parcelle, p.surface_m2, p.lien_rf,
                       p.type_parcelle, p.plan_rf, p.lieu_dit
                FROM dossier_parcelle dp JOIN parcelle p USING (id_parcelle)
                WHERE dp.id_dossier = ANY(CAST(:ids AS text[]))
                ORDER BY dp.id_dossier, p.no_comm, p.no_parcelle
                """)
                .param("ids", dossierIds.toArray(String[]::new))
                .query(rs -> {
                    bases.add(new Base(rs.getString("id_dossier"), rs.getString("id_parcelle"),
                            rs.getString("egrid"), rs.getString("commune"), rs.getInt("no_parcelle"),
                            rs.getObject("surface_m2", Integer.class), rs.getString("lien_rf"),
                            rs.getString("type_parcelle"), rs.getString("plan_rf"), rs.getString("lieu_dit")));
                });
        Map<String, ContexteRepository.Details> details = contexte.detailsOf(
                bases.stream().map(Base::idParcelle).distinct().toList());
        for (Base b : bases) {
            ContexteRepository.Details d = details.get(b.idParcelle());
            byDossier.computeIfAbsent(b.idDossier(), k -> new ArrayList<>()).add(new ParcelleView(
                    b.egrid(), b.commune(), b.numero(), b.surface(), b.lienRf(), b.type(), b.planRf(), b.lieuDit(),
                    d.batiments().stream().map(x -> new ParcelleView.BatimentView(x.egid(), x.numero(),
                            x.destination(), x.surfaceM2(), x.surfaceSurParcelleM2(), x.anneeConstruction(),
                            x.adresses())).toList(),
                    d.nbBatiments(),
                    d.restrictions().stream().map(x -> new ParcelleView.RestrictionView(x.type(), x.code(), x.nom(),
                            x.description(), x.statut(), x.surfaceM2(), x.partPct())).toList()));
        }
        return byDossier;
    }
}
