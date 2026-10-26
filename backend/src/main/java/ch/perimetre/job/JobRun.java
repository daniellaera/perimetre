package ch.perimetre.job;

import java.time.OffsetDateTime;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** A {@code job_run} row, as served by {@code GET /admin/job-runs}. Counters are null on FAILED runs. */
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
        String errorMessage) {

    public enum Trigger {
        SCHEDULED, MANUAL
    }

    public enum Status {
        RUNNING, SUCCESS, FAILED, DISPATCH_FAILED
    }

    /** What a finished run counted; all null when the import itself failed. */
    public record Counters(Integer fetched, Integer changed, Integer notified, Integer pushes, Integer fcmErrors) {

        public static final Counters NONE = new Counters(null, null, null, null, null);
    }
}
