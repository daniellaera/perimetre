package ch.perimetre.event;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {

    private static final String INSERT = """
            INSERT INTO event (id_dossier, kind, old_groupe, new_groupe)
            SELECT * FROM unnest(CAST(:ids AS text[]), CAST(:kinds AS text[]),
                                 CAST(:olds AS text[]), CAST(:news AS text[]))
            RETURNING id
            """;

    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts all events in one statement and returns their ids. */
    public List<Long> insert(List<NewEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(INSERT)
                .param("ids", events.stream().map(NewEvent::idDossier).toArray(String[]::new))
                .param("kinds", events.stream().map(e -> e.kind().name()).toArray(String[]::new))
                .param("olds", events.stream().map(e -> e.oldGroupe() == null ? null : e.oldGroupe().name())
                        .toArray(String[]::new))
                .param("news", events.stream().map(e -> e.newGroupe().name()).toArray(String[]::new))
                .query(Long.class)
                .list();
    }
}
