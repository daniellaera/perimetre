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

/** The generic layer client against a local HTTP server: the query it sends, paging, retries, ArcGIS errors. */
class ArcgisLayerClientTest {

    private static final String FEATURE = "{\"type\":\"Feature\",\"geometry\":null,"
            + "\"properties\":{\"IDPADR\":\"%d\",\"ADRESSE\":\"Rue Test %d\"}}";

    private final List<String> queries = new CopyOnWriteArrayList<>();
    private final List<Integer> statuses = new CopyOnWriteArrayList<>();
    private volatile String errorBody;
    private HttpServer server;

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
            } else if (errorBody != null) {
                body = errorBody.getBytes(StandardCharsets.UTF_8);
            } else if (q.contains("resultOffset=0")) {
                body = ("{\"features\":[" + FEATURE.formatted(1, 1) + "," + FEATURE.formatted(2, 2)
                        + "],\"properties\":{\"exceededTransferLimit\":true}}").getBytes(StandardCharsets.UTF_8);
            } else {
                body = ("{\"features\":[" + FEATURE.formatted(3, 3) + "]}").getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("Content-Type", status == 200 ? "application/json" : "text/html");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ArcgisLayerClient client(String fields, boolean geometry) {
        return new ArcgisLayerClient("TEST", "http://localhost:" + server.getAddress().getPort() + "/query", fields,
                geometry, Duration.ofSeconds(5), d -> { });
    }

    @Test
    void sendsOnlyTheRequestedFieldsAndNoGeometryWhenNotWanted() {
        client("IDPADR,ADRESSE", false).fetch("1=1", page -> { });

        assertThat(queries.get(0)).contains("outFields=IDPADR,ADRESSE", "returnGeometry=false", "outSR=4326",
                "f=geojson", "orderByFields=OBJECTID ASC", "where=1=1", "resultRecordCount=1000");
        assertThat(queries.get(0)).doesNotContain("outFields=*");
    }

    @Test
    void asksForGeometryAtOneCentimetrePrecisionWhenWanted() {
        client("EGID", true).fetch("1=1", page -> { });

        assertThat(queries.get(0)).contains("returnGeometry=true", "geometryPrecision=7");
    }

    @Test
    void followsExceededTransferLimitAndHandsOverPageByPage() {
        List<Integer> sizes = new ArrayList<>();

        long total = client("IDPADR", false).fetch("1=1", page -> sizes.add(page.size()));

        assertThat(total).isEqualTo(3);
        assertThat(sizes).containsExactly(2, 1);
        assertThat(queries.get(1)).contains("resultOffset=2");
    }

    @Test
    void anIncrementalWhereUsesTheTimestampLiteral() {
        assertThat(ArcgisLayerClient.timestampLiteral(Instant.parse("2026-11-06T00:00:00Z")))
                .isEqualTo("TIMESTAMP '2026-11-06 00:00:00'");
    }

    @Test
    void anHttp500IsRetriedOnce() {
        statuses.add(500);

        assertThat(client("IDPADR", false).fetch("1=1", page -> { })).isEqualTo(3);
        assertThat(queries).hasSize(3);
    }

    @Test
    void anArcgisErrorBodyFailsWithoutRetry() {
        errorBody = "{\"error\":{\"code\":400,\"message\":\"Unable to complete operation.\",\"details\":[\"x\"]}}";

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client("IDPADR", false).fetch("1=1", p -> { }))
                .hasMessageContaining("query failed");
        assertThat(queries).hasSize(1);
    }
}
