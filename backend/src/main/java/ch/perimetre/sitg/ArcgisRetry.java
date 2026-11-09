package ch.perimetre.sitg;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Per-request retry policy for SITG's ArcGIS servers, shared by the dossier and parcel imports: up to
 * {@link #MAX_ATTEMPTS} attempts on an I/O error (connection reset, timeout, truncated body), one retry on an
 * HTTP 5xx (bad queries get a 500 maintenance page, which retrying further would not fix), backoff 2 s then
 * 4 s. Anything else (4xx, ArcGIS error bodies) is not retried.
 */
public final class ArcgisRetry {

    private static final Logger log = LoggerFactory.getLogger(ArcgisRetry.class);

    public static final int MAX_ATTEMPTS = 3;
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(2);

    private ArcgisRetry() {
    }

    public static <T> T call(String what, int offset, Consumer<Duration> sleeper, Supplier<T> request) {
        boolean serverErrorRetried = false;
        for (int attempt = 1; ; attempt++) {
            try {
                return request.get();
            } catch (HttpServerErrorException e) {
                if (serverErrorRetried || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                serverErrorRetried = true;
                log.warn("{} returned {} at offset {} (attempt {}), retrying", what, e.getStatusCode(), offset, attempt);
            } catch (RestClientException e) {
                if (!isIoError(e) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("{} I/O error at offset {} (attempt {}), retrying: {}", what, offset, attempt, e.toString());
            }
            sleeper.accept(FIRST_BACKOFF.multipliedBy(1L << (attempt - 1)));
        }
    }

    public static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to retry SITG", e);
        }
    }

    // ResourceAccessException (no response: reset, connect/read timeout) or a body cut off mid-read.
    private static boolean isIoError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof IOException) {
                return true;
            }
        }
        return false;
    }
}
