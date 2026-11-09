package ch.perimetre.parcelle;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * The open layers around the parcels (buildings, addresses, zones, local names), scheduled after the parcel
 * import of the same morning: a daily incremental (the buildings, the only layer with a date) and a weekly
 * full (all four). One {@code job_run} row per layer and run, named {@code batiment|adresse|zone|lieu-dit}
 * + {@code -incremental|-full} ({@code fetched}, {@code changed} = rows inserted, modified or removed,
 * {@code max_date_maj_sitg} = newest DATEDT; the notification counters are 0). One optional healthchecks.io
 * check for the whole sequence, env CONTEXTE_HEALTHCHECK_PING_URL (unset = no-op): it fails when any layer fails.
 * No staleness alarm: these layers are not stamped daily.
 */
@Component
class ContexteJob {

    private static final Logger log = LoggerFactory.getLogger(ContexteJob.class);
    private static final String ZONE = "Europe/Zurich";
    // Run order: buildings first (the only incremental one), then the layers that only need the parcels.
    private static final List<String> ORDER = List.of("batiment", "adresse", "zone", "lieu-dit");

    private final List<LayerImporter> importers;
    private final JobRunRepository runs;
    private final HealthcheckPinger pinger;
    private final ReentrantLock lock = new ReentrantLock();

    ContexteJob(List<LayerImporter> importers, JobRunRepository runs,
            @Value("${CONTEXTE_HEALTHCHECK_PING_URL:}") String pingUrl) {
        this.importers = importers.stream()
                .sorted(java.util.Comparator.comparingInt(i -> ORDER.indexOf(i.jobName()))).toList();
        this.runs = runs;
        this.pinger = HealthcheckPinger.forUrl(pingUrl, "CONTEXTE_HEALTHCHECK_PING_URL");
    }

    record RunResult(String source, long jobRunId, ImportMode mode, long fetched, long changed, long removed,
            long durationMs) {
    }

    @ResponseStatus(HttpStatus.CONFLICT)
    static class AlreadyRunningException extends RuntimeException {
        AlreadyRunningException() {
            super("a parcel-context import is already running");
        }
    }

    // Crons in application.yml (env CONTEXTE_INCREMENTAL_CRON / CONTEXTE_FULL_CRON); the zone is fixed here
    // (ScheduledZoneTest).
    @Scheduled(cron = "${perimetre.contexte.incremental-cron}", zone = ZONE)
    void scheduledIncremental() {
        runScheduled(ImportMode.INCREMENTAL);
    }

    @Scheduled(cron = "${perimetre.contexte.full-cron}", zone = ZONE)
    void scheduledFull() {
        runScheduled(ImportMode.FULL);
    }

    /** The layer named by {@code key} (batiments, adresses, zones, lieux-dits), or all of them when null/"all". */
    List<LayerImporter> select(String key) {
        if (key == null || key.isBlank() || key.equalsIgnoreCase("all")) {
            return importers;
        }
        List<LayerImporter> found = importers.stream().filter(i -> keyOf(i).equalsIgnoreCase(key)).toList();
        if (found.isEmpty()) {
            throw new IllegalArgumentException("unknown source '" + key + "' (batiments, adresses, zones, "
                    + "lieux-dits or all)");
        }
        return found;
    }

    static String keyOf(LayerImporter i) {
        return switch (i.jobName()) {
            case "lieu-dit" -> "lieux-dits";
            default -> i.jobName() + "s";
        };
    }

    List<RunResult> run(List<LayerImporter> selected, ImportMode mode, Trigger trigger) {
        if (!lock.tryLock()) {
            throw new AlreadyRunningException();
        }
        try {
            List<RunResult> results = new ArrayList<>();
            RuntimeException failure = null;
            for (LayerImporter importer : selected) {
                try {
                    results.add(runOne(importer, mode, trigger));
                } catch (RuntimeException e) {
                    if (trigger == Trigger.MANUAL) {
                        throw e;
                    }
                    failure = failure == null ? e : failure;
                }
            }
            if (failure != null) {
                throw failure;
            }
            return results;
        } finally {
            lock.unlock();
        }
    }

    private RunResult runOne(LayerImporter importer, ImportMode requested, Trigger trigger) {
        long start = System.nanoTime();
        Long runId = null;
        String jobName = importer.jobName() + "-" + requested.name().toLowerCase(Locale.ROOT);
        try {
            ImportMode mode = importer.effectiveMode(requested);
            jobName = importer.jobName() + "-" + mode.name().toLowerCase(Locale.ROOT);
            runId = runs.start(jobName, trigger);
            LayerImporter.Result r = importer.run(mode);
            runs.finish(runId, Status.SUCCESS, new Counters(Math.toIntExact(r.fetched()),
                    Math.toIntExact(r.changed()), 0, 0, 0, r.maxDate()), null);
            log.info("Job run {} {} ({}) SUCCESS: fetched={} changed={} removed={} max_date_mut={} durationMs={}",
                    runId, jobName, trigger, r.fetched(), r.changed(), r.removed(), r.maxDate(), elapsed(start));
            return new RunResult(importer.source(), runId, r.mode(), r.fetched(), r.changed(), r.removed(),
                    elapsed(start));
        } catch (RuntimeException e) {
            if (runId != null) {
                try {
                    runs.finish(runId, Status.FAILED, Counters.NONE, e.toString());
                } catch (RuntimeException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            log.info("Job run {} {} ({}) FAILED: durationMs={} error={}", runId == null ? "-" : runId, jobName,
                    trigger, elapsed(start), e);
            throw e;
        }
    }

    private void runScheduled(ImportMode mode) {
        List<LayerImporter> selected = mode == ImportMode.FULL ? importers
                : importers.stream().filter(LayerImporter::supportsIncremental).toList();
        pinger.start();
        try {
            List<RunResult> results = run(selected, mode, Trigger.SCHEDULED);
            pinger.success("parcel-context %s: %s".formatted(mode, results.stream()
                    .map(r -> r.source() + " fetched=" + r.fetched() + " changed=" + r.changed()).toList()));
        } catch (AlreadyRunningException e) {
            log.warn("Scheduled parcel-context {} import skipped: another one is running", mode);
        } catch (RuntimeException e) {
            log.error("Scheduled parcel-context {} import failed: {}", mode, e.toString());
            pinger.fail("parcel-context " + mode + " failed: " + e);
        }
    }

    private static long elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
