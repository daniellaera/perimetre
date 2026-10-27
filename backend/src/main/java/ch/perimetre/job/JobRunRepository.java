package ch.perimetre.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import ch.perimetre.job.JobRun.Counters;
import ch.perimetre.job.JobRun.Status;
import ch.perimetre.job.JobRun.Trigger;

@Repository
public class JobRunRepository {

    private static final Logger log = LoggerFactory.getLogger(JobRunRepository.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final JdbcClient jdbc;

    JobRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts a RUNNING row and returns its id. */
    public long start(String jobName, Trigger trigger) {
        return jdbc.sql("INSERT INTO job_run (job_name, trigger, status) VALUES (?, ?, 'RUNNING') RETURNING id")
                .params(jobName, trigger.name())
                .query(Long.class)
                .single();
    }

    public void finish(long id, Status status, Counters counters, String errorMessage) {
        jdbc.sql("""
                UPDATE job_run SET finished_at = now(), status = ?, fetched = ?, changed = ?, notified = ?,
                       pushes = ?, fcm_errors = ?, max_date_maj_sitg = ?, error_message = ?
                WHERE id = ?""")
                .params(status.name(), counters.fetched(), counters.changed(), counters.notified(),
                        counters.pushes(), counters.fcmErrors(), counters.maxDateMajSitg(), truncate(errorMessage), id)
                .update();
    }

    /** Newest first. */
    public List<JobRun> latest(int limit) {
        return jdbc.sql("""
                SELECT *, (extract(epoch FROM finished_at - started_at) * 1000)::bigint AS duration_ms
                FROM job_run ORDER BY started_at DESC, id DESC LIMIT ?""")
                .param(limit)
                .query(JobRunRepository::map)
                .list();
    }

    /** Runs are in-process (one backend), so a RUNNING row at startup was cut off by a restart. */
    @EventListener(ApplicationReadyEvent.class)
    void markInterrupted() {
        int rows = jdbc.sql("""
                UPDATE job_run SET status = 'FAILED', finished_at = now(),
                       error_message = 'interrupted: the backend stopped during the run'
                WHERE status = 'RUNNING'""")
                .update();
        if (rows > 0) {
            log.warn("Marked {} job_run row(s) left RUNNING by a restart as FAILED", rows);
        }
    }

    private static JobRun map(ResultSet rs, int rowNum) throws SQLException {
        return new JobRun(
                rs.getLong("id"),
                rs.getString("job_name"),
                Trigger.valueOf(rs.getString("trigger")),
                rs.getObject("started_at", OffsetDateTime.class),
                rs.getObject("finished_at", OffsetDateTime.class),
                rs.getObject("duration_ms", Long.class),
                Status.valueOf(rs.getString("status")),
                rs.getObject("fetched", Integer.class),
                rs.getObject("changed", Integer.class),
                rs.getObject("notified", Integer.class),
                rs.getObject("pushes", Integer.class),
                rs.getObject("fcm_errors", Integer.class),
                rs.getObject("max_date_maj_sitg", LocalDate.class),
                rs.getString("error_message"));
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
