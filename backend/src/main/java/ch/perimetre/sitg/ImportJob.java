package ch.perimetre.sitg;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ResponseStatus;

import ch.perimetre.job.HealthcheckPinger;
import ch.perimetre.job.JobRun.Counters;
import ch.perimetre.job.JobRun.Status;
import ch.perimetre.job.JobRun.Trigger;
import ch.perimetre.job.JobRunRepository;
import ch.perimetre.notification.NotificationDispatcher;
import ch.perimetre.notification.NotificationDispatcher.DispatchResult;

/**
 * Import followed by notification dispatch; scheduled, and serialised by an in-process lock. Every run
 * writes one {@code job_run} row and one INFO summary line; scheduled runs also ping HEALTHCHECK_PING_URL.
 */
@Component
class ImportJob {

    private static final Logger log = LoggerFactory.getLogger(ImportJob.class);
    private static final String ZONE = "Europe/Zurich";

    private final SitgImporter importer;
    private final NotificationDispatcher dispatcher;
    private final JobRunRepository runs;
    private final HealthcheckPinger pinger;
    private final ReentrantLock lock = new ReentrantLock();

    ImportJob(SitgImporter importer, NotificationDispatcher dispatcher, JobRunRepository runs,
            HealthcheckPinger pinger) {
        this.importer = importer;
        this.dispatcher = dispatcher;
        this.runs = runs;
        this.pinger = pinger;
    }

    record RunResult(long jobRunId, ImportMode mode, long fetched, long inserted, long updated, int events,
            int notifications, int pushes, int fcmErrors, long durationMs) {
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    static class ImportAlreadyRunningException extends RuntimeException {
        ImportAlreadyRunningException() {
            super("an import is already running");
        }
    }

    @Scheduled(cron = "0 15 3 * * *", zone = ZONE)
    void scheduledIncremental() {
        runScheduled(ImportMode.INCREMENTAL);
    }

    @Scheduled(cron = "0 45 3 * * SUN", zone = ZONE)
    void scheduledFull() {
        runScheduled(ImportMode.FULL);
    }

    /**
     * Runs the import, then dispatches the events it created. A dispatch failure is logged but does not fail
     * the run: the import is committed and its notifications stay PENDING (the job_run row says
     * DISPATCH_FAILED).
     *
     * @throws ImportAlreadyRunningException when another run holds the lock
     */
    RunResult run(ImportMode mode, Trigger trigger) {
        if (!lock.tryLock()) {
            throw new ImportAlreadyRunningException();
        }
        try {
            return runLocked(mode, trigger);
        } finally {
            lock.unlock();
        }
    }

    private RunResult runLocked(ImportMode mode, Trigger trigger) {
        String jobName = "import-" + mode.name().toLowerCase(Locale.ROOT);
        boolean ping = trigger == Trigger.SCHEDULED;
        if (ping) {
            pinger.start();
        }
        long start = System.nanoTime();
        Long runId = null;
        try {
            runId = runs.start(jobName, trigger);
            SitgImporter.ImportResult imported = importer.run(mode);
            DispatchResult dispatched;
            String dispatchError = null;
            try {
                dispatched = dispatcher.dispatch(imported.eventIds());
            } catch (RuntimeException e) {
                log.error("Notification dispatch failed for {} events", imported.eventIds().size(), e);
                dispatched = DispatchResult.NONE;
                dispatchError = "dispatch failed: " + e;
            }
            Status status = dispatchError == null ? Status.SUCCESS : Status.DISPATCH_FAILED;
            Counters counters = new Counters(Math.toIntExact(imported.fetched()), imported.eventIds().size(),
                    dispatched.notifications(), dispatched.pushes(), dispatched.pushErrors());
            runs.finish(runId, status, counters, dispatchError);
            long durationMs = elapsedMs(start);
            String summary = summary(runId, jobName, trigger, status, counters, durationMs);
            log.info(summary);
            if (ping) {
                if (status == Status.SUCCESS) {
                    pinger.success(summary);
                } else {
                    pinger.fail(summary + "\n" + dispatchError);
                }
            }
            return new RunResult(runId, mode, imported.fetched(), imported.inserted(), imported.updated(),
                    imported.eventIds().size(), dispatched.notifications(), dispatched.pushes(),
                    dispatched.pushErrors(), durationMs);
        } catch (RuntimeException e) {
            if (runId != null) {
                try {
                    runs.finish(runId, Status.FAILED, Counters.NONE, e.toString());
                } catch (RuntimeException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            String summary = summary(runId, jobName, trigger, Status.FAILED, Counters.NONE, elapsedMs(start))
                    + " error=" + e;
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
        } catch (ImportAlreadyRunningException e) {
            log.warn("Scheduled {} import skipped: another import is running", mode);
        } catch (RuntimeException e) {
            // SitgImporter has already logged the failure with its stack trace.
            log.error("Scheduled {} import failed: {}", mode, e.toString());
        }
    }

    private static String summary(Long runId, String jobName, Trigger trigger, Status status, Counters c,
            long durationMs) {
        return "Job run %s %s (%s) %s: fetched=%s changed=%s notified=%s pushes=%s fcm_errors=%s durationMs=%d"
                .formatted(runId == null ? "-" : runId, jobName, trigger, status, c.fetched(), c.changed(),
                        c.notified(), c.pushes(), c.fcmErrors(), durationMs);
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
