package ch.perimetre.sitg;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.stereotype.Component;

/**
 * One INFO line per registered cron task at startup (expression and next run in Europe/Zurich), read from
 * the scheduler itself, so the log proves the jobs are actually scheduled.
 */
@Component
class ScheduleReport {

    private static final Logger log = LoggerFactory.getLogger(ScheduleReport.class);
    private static final ZoneId ZURICH = ZoneId.of("Europe/Zurich");

    private final List<ScheduledTaskHolder> holders;

    ScheduleReport(List<ScheduledTaskHolder> holders) {
        this.holders = holders;
    }

    @EventListener(ApplicationReadyEvent.class)
    void report() {
        for (ScheduledTaskHolder holder : holders) {
            for (ScheduledTask task : holder.getScheduledTasks()) {
                if (task.getTask() instanceof CronTask cron) {
                    // nextExecution() is now + remaining delay, a few µs before the trigger time.
                    Instant next = task.nextExecution();
                    log.info("Scheduled {} cron \"{}\", next run {}", cron.getRunnable(), cron.getExpression(),
                            next == null ? "-" : next.plusMillis(500).truncatedTo(ChronoUnit.SECONDS).atZone(ZURICH));
                }
            }
        }
    }
}
