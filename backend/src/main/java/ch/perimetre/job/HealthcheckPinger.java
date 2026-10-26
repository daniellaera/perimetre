package ch.perimetre.job;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Dead-man's switch for the scheduled imports, healthchecks.io style: {@code <url>/start} when a run
 * starts, {@code <url>} on success, {@code <url>/fail} on failure, each with a short text body. The URL
 * comes from the env var HEALTHCHECK_PING_URL; unset or blank means every ping is a no-op. A ping that
 * fails is logged (without the URL, whose path is the secret) and never fails the run.
 */
@Component
public class HealthcheckPinger {

    private static final Logger log = LoggerFactory.getLogger(HealthcheckPinger.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String url;
    private final RestClient client;

    @Autowired
    HealthcheckPinger(@Value("${HEALTHCHECK_PING_URL:}") String url) {
        this(url, RestClient.builder().requestFactory(timeouts()));
    }

    HealthcheckPinger(String url, RestClient.Builder builder) {
        String trimmed = url == null ? "" : url.trim();
        this.url = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        this.client = builder.build();
        log.info(this.url.isEmpty() ? "HEALTHCHECK_PING_URL not set: no dead-man's-switch pings"
                : "Scheduled imports ping HEALTHCHECK_PING_URL");
    }

    public void start() {
        ping("/start", "");
    }

    public void success(String body) {
        ping("", body);
    }

    public void fail(String body) {
        ping("/fail", body);
    }

    private void ping(String suffix, String body) {
        if (url.isEmpty()) {
            return;
        }
        try {
            client.post().uri(url + suffix).body(body).retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            log.warn("Healthcheck ping '{}' failed: {}", suffix.isEmpty() ? "success" : suffix, e.toString());
        }
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        return factory;
    }
}
