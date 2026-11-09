package ch.perimetre.parcelle;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ResponseStatus;

import ch.perimetre.job.HealthcheckPinger;
import ch.perimetre.job.JobRun.Counters;
import ch.perimetre.job.JobRun.Status;
import ch.perimetre.job.JobRun.Trigger;
import ch.perimetre.job.JobRunRepository;
import ch.perimetre.sitg.ImportMode;

/**
 * Parcel import, scheduled like the dossier one: a daily incremental and a weekly full, serialised by an
 * in-process lock, one {@code job_run} row per run ({@code parcelle-incremental} / {@code parcelle-full}:
 * {@code fetched} = parcels fetched, {@code changed} = parcels inserted, modified or removed,
 * {@code max_date_maj_sitg} = newest DATEDT; {@code notified}, {@code pushes}, {@code fcm_errors} = 0).
 * Scheduled runs ping their own healthchecks.io check, env PARCELLE_HEALTHCHECK_PING_URL (unset = no-op).
 * There is no staleness alarm: unlike dossiers, parcels are not stamped every day.
 */
@Component
class ParcelleJob {

    private static final Logger log = LoggerFactory.getLogger(ParcelleJob.class);
    private static final String ZONE = "Europe/Zurich";

    private final ParcelleImporter importer;
    private final JobRunRepository runs;
    private final HealthcheckPinger pinger;
    private final ReentrantLock lock = new ReentrantLock();

    ParcelleJob(ParcelleImporter importer, JobRunRepository runs,
            @Value("${PARCELLE_HEALTHCHECK_PING_URL:}") String pingUrl) {
        this.importer = importer;
        this.runs = runs;
        this.pinger = HealthcheckPinger.forUrl(pingUrl, "PARCELLE_HEALTHCHECK_PING_URL");
    }

    record RunResult(long jobRunId, ImportMode mode, long fetched, long changed, long removed, long relinked,
            long durationMs) {
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    static class AlreadyRunningException extends RuntimeException {
        AlreadyRunningException() {
            super("a parcel import is already running");
        }
    }

    // Crons in application.yml (env PARCELLE_INCREMENTAL_CRON / PARCELLE_FULL_CRON); the zone is fixed here
    // (ScheduledZoneTest). Both run after the dossier imports of the same morning.
    @Scheduled(cron = "${perimetre.parcelle.incremental-cron}", zone = ZONE)
    void scheduledIncremental() {
        runScheduled(ImportMode.INCREMENTAL);
    }

    @Scheduled(cron = "${perimetre.parcelle.full-cron}", zone = ZONE)
    void scheduledFull() {
        runScheduled(ImportMode.FULL);
    }

    RunResult run(ImportMode mode, Trigger trigger) {
        if (!lock.tryLock()) {
            throw new AlreadyRunningException();
        }
        try {
            return runLocked(mode, trigger);
        } finally {
            lock.unlock();
        }
    }

    private RunResult runLocked(ImportMode requested, Trigger trigger) {
        boolean ping = trigger == Trigger.SCHEDULED;
        if (ping) {
            pinger.start();
        }
        long start = System.nanoTime();
        Long runId = null;
        String jobName = "parcelle-" + requested.name().toLowerCase(Locale.ROOT);
        try {
            // The very first run is full whatever was asked: the job_run row names the mode that really runs.
            ImportMode mode = importer.effectiveMode(requested);
            jobName = "parcelle-" + mode.name().toLowerCase(Locale.ROOT);
            runId = runs.start(jobName, trigger);
            ParcelleImporter.ImportResult r = importer.run(mode);
            Counters counters = new Counters(Math.toIntExact(r.fetched()), Math.toIntExact(r.changed()), 0, 0, 0,
                    r.maxDateMut());
            runs.finish(runId, Status.SUCCESS, counters, null);
            long durationMs = elapsedMs(start);
            String summary = "Job run %s %s (%s) SUCCESS: fetched=%d changed=%d removed=%d relinked=%d max_date_mut=%s durationMs=%d"
                    .formatted(runId, jobName, trigger, r.fetched(), r.changed(), r.removed(), r.relinked(),
                            r.maxDateMut(), durationMs);
            log.info(summary);
            if (ping) {
                pinger.success(summary);
            }
            return new RunResult(runId, r.mode(), r.fetched(), r.changed(), r.removed(), r.relinked(), durationMs);
        } catch (RuntimeException e) {
            if (runId != null) {
                try {
                    runs.finish(runId, Status.FAILED, Counters.NONE, e.toString());
                } catch (RuntimeException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            String summary = "Job run %s %s (%s) FAILED: durationMs=%d error=%s"
                    .formatted(runId == null ? "-" : runId, jobName, trigger, elapsedMs(start), e);
            log.info(summary);
            if (ping) {
                pinger.fail(summary);
            }
            throw e;
        }
    }

    private void runScheduled(ImportMode mode) {
        try {
            run(mode, Trigger.SCHEDULED);
        } catch (AlreadyRunningException e) {
            log.warn("Scheduled parcel {} import skipped: another parcel import is running", mode);
        } catch (RuntimeException e) {
            // ParcelleImporter has already logged the failure with its stack trace.
            log.error("Scheduled parcel {} import failed: {}", mode, e.toString());
        }
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
