package ch.perimetre.parcelle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** ParcelleClient against a local HTTP server: paging, the query it sends, retries, page-by-page delivery. */
class ParcelleClientTest {

    private static final String FEATURE = """
            {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[6.1,46.2],[6.1,46.3],[6.2,46.3],[6.1,46.2]]]},
             "properties":{"EGRID":"CH1","COMMUNE":"Testville","NO_COMM":9,"NO_PARCELLE":%d,"IDEDDP":"9:%d",
                           "SURFACE":100,"DATEDT":1793059200000,"LIEN_WWW":"https://example.test/%d"}}""";

    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final List<Integer> statuses = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private ParcelleClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/query", exchange -> {
            String q = URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            queries.add(q);
            int status = statuses.isEmpty() ? 200 : statuses.remove(0);
            byte[] body;
            if (status != 200) {
                body = "<html>maintenance</html>".getBytes(StandardCharsets.UTF_8);
            } else {
                // A short first page that still says exceededTransferLimit, then a last short page.
                boolean first = q.contains("resultOffset=0");
                body = (first
                        ? "{\"features\":[" + FEATURE.formatted(1, 1, 1) + "," + FEATURE.formatted(2, 2, 2)
                                + "],\"properties\":{\"exceededTransferLimit\":true}}"
                        : "{\"features\":[" + FEATURE.formatted(3, 3, 3) + "]}").getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", status == 200 ? "application/json" : "text/html");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new ParcelleClient("http://localhost:" + server.getAddress().getPort() + "/query",
                Duration.ofSeconds(5), d -> { });
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void followsExceededTransferLimitAndHandsOverPageByPage() {
        List<Integer> pageSizes = new ArrayList<>();

        long total = client.fetch(null, page -> pageSizes.add(page.size()));

        assertThat(total).isEqualTo(3);
        assertThat(pageSizes).containsExactly(2, 1);
        assertThat(queries).hasSize(2);
        assertThat(queries.get(1)).contains("resultOffset=2");
    }

    @Test
    void asksOnlyForParcelsInForceAndOnlyOpenAttributes() {
        client.fetch(null, page -> { });

        String q = queries.get(0);
        assertThat(q).contains("where=VALIDITE = 'en vigueur'").doesNotContain("DATEDT >=");
        assertThat(q).contains("outSR=4326", "f=geojson", "orderByFields=OBJECTID ASC");
        // The owner-related columns of the layer are never requested.
        assertThat(q).doesNotContain("PROPRI").doesNotContain("PROPRIETAIRE");
        assertThat(q).contains("outFields=EGRID,COMMUNE,NO_COMM,NO_PARCELLE,IDEDDP,SURFACE,DATEDT,LIEN_WWW");
    }

    @Test
    void anIncrementalQueryFiltersOnTheMutationDate() {
        client.fetch(Instant.parse("2026-11-06T09:15:30Z"), page -> { });

        assertThat(queries.get(0)).contains("VALIDITE = 'en vigueur' AND DATEDT >= TIMESTAMP '2026-11-06 09:15:30'");
    }

    @Test
    void anHttp500IsRetriedOnce() {
        statuses.add(500);

        long total = client.fetch(null, page -> { });

        assertThat(total).isEqualTo(3);
        assertThat(queries).hasSize(3);
    }
}
