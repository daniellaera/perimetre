package ch.perimetre.job;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * A {@code job_run} row, as served by {@code GET /admin/job-runs}. Counters are null on FAILED runs;
 * {@code maxDateMajSitg} is the newest {@code date_maj_2} the run fetched (source freshness).
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record JobRun(
        long id,
        String jobName,
        Trigger trigger,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        Long durationMs,
        Status status,
        Integer fetched,
        Integer changed,
        Integer notified,
        Integer pushes,
        Integer fcmErrors,
        LocalDate maxDateMajSitg,
        String errorMessage) {

    public enum Trigger {
        SCHEDULED, MANUAL
    }

    public enum Status {
        // STALE_SOURCE: the run completed, but the newest date_maj_2 it fetched is older than 3 days.
        RUNNING, SUCCESS, FAILED, DISPATCH_FAILED, STALE_SOURCE
    }

    /** What a finished run counted and the newest date_maj_2 it saw; all null when the import itself failed. */
    public record Counters(Integer fetched, Integer changed, Integer notified, Integer pushes, Integer fcmErrors,
            LocalDate maxDateMajSitg) {

        public static final Counters NONE = new Counters(null, null, null, null, null, null);
    }
}
