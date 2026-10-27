package ch.perimetre.sitg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * SitgClient's retries against a raw local HTTP/1.1 server, so the real JDK client sees a real connection
 * reset, read timeout or truncated body (the 2026-10-27 dry run failed on "Connection reset"). The JDK
 * client sometimes retries a GET once by itself when a connection closes before any response byte, so
 * attempts and backoff are counted with read timeouts, which it never retries.
 */
class SitgClientRetryTest {

    private static final String PAGE = """
            {"type":"FeatureCollection","features":[{"type":"Feature",
             "geometry":{"type":"Point","coordinates":[6.15,46.2]},
             "properties":{"id_dossier":"APA 1/1","type_dossier":"APA","statut":"ENREGISTREMENT",
                           "date_depot":1793059200000,"date_maj_2":1793059200000}}]}""";

    enum Reply { RESET, HANG, TRUNCATED, ERROR_500, ARCGIS_ERROR, OK }

    private final ConcurrentLinkedQueue<Reply> script = new ConcurrentLinkedQueue<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    private final List<Socket> open = new CopyOnWriteArrayList<>();
    private ServerSocket server;
    private SitgClient client;

    @BeforeEach
    void start() throws IOException {
        server = new ServerSocket(0);
        Thread.ofVirtual().start(this::acceptLoop);
        client = new SitgClient("http://localhost:" + server.getLocalPort() + "/query", Duration.ofMillis(500),
                sleeps::add);
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
        for (Socket s : open) {
            s.close();
        }
    }

    @Test
    void connectionResetsAreRetried() {
        script(Reply.RESET, Reply.RESET, Reply.OK);

        List<SitgPage.Feature> features = client.fetchModifiedSince(Instant.parse("2026-10-24T02:15:00Z"));

        assertThat(features).singleElement().satisfies(f -> assertThat(f.properties().idDossier()).isEqualTo("APA 1/1"));
        assertThat(sleeps).isNotEmpty();
    }

    @Test
    void timeoutsAreRetriedWithBackoff() {
        script(Reply.HANG, Reply.HANG, Reply.OK);

        assertThat(client.fetchModifiedSince(Instant.EPOCH)).hasSize(1);
        assertThat(requests).hasValue(3);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void truncatedBodiesAreRetried() {
        script(Reply.TRUNCATED, Reply.OK);

        assertThat(client.fetchModifiedSince(Instant.EPOCH)).hasSize(1);
        assertThat(requests).hasValue(2);
    }

    @Test
    void givesUpAfterThreeAttempts() {
        script(Reply.HANG, Reply.HANG, Reply.HANG, Reply.OK);

        assertThatThrownBy(() -> client.fetchModifiedSince(Instant.EPOCH)).isInstanceOf(ResourceAccessException.class);
        assertThat(requests).hasValue(SitgClient.MAX_ATTEMPTS);
        assertThat(sleeps).hasSize(SitgClient.MAX_ATTEMPTS - 1);
    }

    @Test
    void serverErrorsAreStillRetriedOnlyOnce() {
        script(Reply.ERROR_500, Reply.ERROR_500, Reply.OK);

        assertThatThrownBy(() -> client.fetchModifiedSince(Instant.EPOCH)).isInstanceOf(HttpServerErrorException.class);
        assertThat(requests).hasValue(2);
    }

    @Test
    void anIoErrorAfterAServerErrorIsStillRetried() {
        script(Reply.ERROR_500, Reply.HANG, Reply.OK);

        assertThat(client.fetchModifiedSince(Instant.EPOCH)).hasSize(1);
        assertThat(requests).hasValue(3);
    }

    @Test
    void arcgisErrorBodiesAreNotRetried() {
        script(Reply.ARCGIS_ERROR, Reply.OK);

        assertThatThrownBy(() -> client.fetchModifiedSince(Instant.EPOCH))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("400 Invalid query");
        assertThat(requests).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    private void script(Reply... replies) {
        script.addAll(List.of(replies));
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket socket = server.accept();
                open.add(socket);
                Thread.ofVirtual().start(() -> serve(socket));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void serve(Socket socket) {
        try (socket) {
            readRequestHead(socket.getInputStream());
            requests.incrementAndGet();
            Reply reply = script.poll();
            OutputStream out = socket.getOutputStream();
            switch (reply == null ? Reply.OK : reply) {
                case RESET -> socket.setSoLinger(true, 0); // close() now sends RST before any response byte
                case HANG -> Thread.sleep(5_000);
                case TRUNCATED -> {
                    out.write(head(200, PAGE.length()));
                    out.write(PAGE.substring(0, 40).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                case ERROR_500 -> write(out, 500, "<html>maintenance</html>");
                case ARCGIS_ERROR -> write(out, 200, """
                        {"error":{"code":400,"message":"Invalid query","details":[]}}""");
                case OK -> write(out, 200, PAGE);
            }
        } catch (IOException | InterruptedException ignored) {
            // the client gave up on this connection
        }
    }

    private static void readRequestHead(InputStream in) throws IOException {
        List<Integer> last = new ArrayList<>();
        int b;
        while ((b = in.read()) != -1) {
            last.add(b);
            int n = last.size();
            if (n >= 4 && last.get(n - 4) == '\r' && last.get(n - 3) == '\n' && last.get(n - 2) == '\r'
                    && last.get(n - 1) == '\n') {
                return;
            }
        }
    }

    private static void write(OutputStream out, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        out.write(head(status, bytes.length));
        out.write(bytes);
        out.flush();
    }

    private static byte[] head(int status, int contentLength) {
        return ("HTTP/1.1 " + status + (status == 200 ? " OK" : " Internal Server Error") + "\r\n"
                + "Content-Type: application/json\r\nContent-Length: " + contentLength + "\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8);
    }
}
