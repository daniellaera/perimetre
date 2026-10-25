package ch.perimetre.sitg;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.ResponseStatus;

import ch.perimetre.notification.NotificationDispatcher;
import ch.perimetre.notification.NotificationDispatcher.DispatchResult;

/** Import followed by notification dispatch; scheduled, and serialised by an in-process lock. */
@Component
class ImportJob {

    private static final Logger log = LoggerFactory.getLogger(ImportJob.class);
    private static final String ZONE = "Europe/Zurich";

    private final SitgImporter importer;
    private final NotificationDispatcher dispatcher;
    private final ReentrantLock lock = new ReentrantLock();

    ImportJob(SitgImporter importer, NotificationDispatcher dispatcher) {
        this.importer = importer;
        this.dispatcher = dispatcher;
    }

    record RunResult(ImportMode mode, long fetched, long inserted, long updated, int events, int notifications,
            int pushes, long durationMs) {
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
     * the run: the import is committed and its notifications stay PENDING.
     *
     * @throws ImportAlreadyRunningException when another run holds the lock
     */
    RunResult run(ImportMode mode) {
        if (!lock.tryLock()) {
            throw new ImportAlreadyRunningException();
        }
        try {
            long start = System.nanoTime();
            SitgImporter.ImportResult imported = importer.run(mode);
            DispatchResult dispatched;
            try {
                dispatched = dispatcher.dispatch(imported.eventIds());
            } catch (RuntimeException e) {
                log.error("Notification dispatch failed for {} events", imported.eventIds().size(), e);
                dispatched = new DispatchResult(0, 0);
            }
            return new RunResult(mode, imported.fetched(), imported.inserted(), imported.updated(),
                    imported.eventIds().size(), dispatched.notifications(), dispatched.pushes(),
                    Duration.ofNanos(System.nanoTime() - start).toMillis());
        } finally {
            lock.unlock();
        }
    }

    private void runScheduled(ImportMode mode) {
        try {
            log.info("Scheduled {} import: {}", mode, run(mode));
        } catch (ImportAlreadyRunningException e) {
            log.warn("Scheduled {} import skipped: another import is running", mode);
        } catch (RuntimeException e) {
            // SitgImporter has already logged the failure with its stack trace.
            log.error("Scheduled {} import failed: {}", mode, e.toString());
        }
    }
}
