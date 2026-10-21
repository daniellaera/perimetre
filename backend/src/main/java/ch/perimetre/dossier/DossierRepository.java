package ch.perimetre.dossier;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DossierRepository {

    private static final int BATCH_SIZE = 500;

    private static final String UPSERT = """
            INSERT INTO dossier (id_dossier, type_dossier, type_operation, operation, description,
                                 statut_sitg, groupe, date_depot, date_maj_sitg, lien_sad, geom, flags)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326), ?)
            ON CONFLICT (id_dossier) DO UPDATE SET
                type_dossier   = EXCLUDED.type_dossier,
                type_operation = EXCLUDED.type_operation,
                operation      = EXCLUDED.operation,
                description    = EXCLUDED.description,
                statut_sitg    = EXCLUDED.statut_sitg,
                groupe         = EXCLUDED.groupe,
                date_depot     = EXCLUDED.date_depot,
                date_maj_sitg  = EXCLUDED.date_maj_sitg,
                lien_sad       = EXCLUDED.lien_sad,
                geom           = EXCLUDED.geom,
                flags          = EXCLUDED.flags,
                last_seen      = now()
            """;

    // geom::geography must stay written exactly like this to match the dossier_geog_gist expression index.
    private static final String WITHIN = """
            SELECT id_dossier, type_dossier, type_operation, operation, description,
                   groupe, date_depot, date_maj_sitg, flags, lien_sad,
                   ST_Y(geom) AS lat, ST_X(geom) AS lng,
                   ST_Distance(geom::geography, p.center) AS distance_m
            FROM dossier,
                 (SELECT ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography AS center) p
            WHERE groupe NOT IN ('IGNORE', 'TERMINE')
              AND ST_DWithin(geom::geography, p.center, :radius)
            ORDER BY date_maj_sitg DESC, distance_m ASC, id_dossier ASC
            LIMIT 200
            """;

    private final JdbcClient jdbc;
    // JdbcClient has no batch API; JdbcTemplate is used for the batched upsert only.
    private final JdbcTemplate jdbcTemplate;

    public DossierRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM dossier").query(Long.class).single();
    }

    public void upsert(List<Dossier> dossiers) {
        jdbcTemplate.batchUpdate(UPSERT, dossiers, BATCH_SIZE, (ps, d) -> {
            ps.setString(1, d.idDossier());
            ps.setString(2, d.typeDossier());
            ps.setString(3, d.typeOperation());
            ps.setString(4, d.operation());
            ps.setString(5, d.description());
            ps.setString(6, d.statutSitg());
            ps.setString(7, d.groupe().name());
            ps.setObject(8, d.dateDepot());
            ps.setObject(9, d.dateMajSitg());
            ps.setString(10, d.lienSad());
            ps.setDouble(11, d.lng());
            ps.setDouble(12, d.lat());
            ps.setArray(13, ps.getConnection().createArrayOf("text", d.flags().toArray()));
        });
    }

    public List<DossierView> findWithin(double lat, double lng, int radiusMetres) {
        return jdbc.sql(WITHIN)
                .param("lat", lat)
                .param("lng", lng)
                .param("radius", radiusMetres)
                .query(DossierRepository::mapView)
                .list();
    }

    private static DossierView mapView(ResultSet rs, int rowNum) throws SQLException {
        return new DossierView(
                rs.getString("id_dossier"),
                rs.getString("type_dossier"),
                rs.getString("type_operation"),
                rs.getString("operation"),
                rs.getString("description"),
                Groupe.valueOf(rs.getString("groupe")),
                rs.getObject("date_depot", LocalDate.class),
                rs.getObject("date_maj_sitg", OffsetDateTime.class),
                List.of((String[]) rs.getArray("flags").getArray()),
                rs.getString("lien_sad"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getDouble("distance_m"));
    }
}
