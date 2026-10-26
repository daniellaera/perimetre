package ch.perimetre;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;

/** Every cron {@code @Scheduled} under ch.perimetre names its zone: the JVM's default zone must not matter. */
class ScheduledZoneTest {

    @Test
    void everyCronNamesEuropeZurich() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> true);
        List<String> crons = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("ch.perimetre")) {
            for (Method method : Class.forName(candidate.getBeanClassName()).getDeclaredMethods()) {
                for (Scheduled scheduled : AnnotatedElementUtils.findMergedRepeatableAnnotations(method, Scheduled.class)) {
                    if (!scheduled.cron().isEmpty()) {
                        crons.add(method.getName());
                        assertThat(scheduled.zone()).as(method.toString()).isEqualTo("Europe/Zurich");
                    }
                }
            }
        }
        assertThat(crons).contains("scheduledIncremental", "scheduledFull");
    }
}
