package ch.perimetre.parcelle;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import ch.perimetre.sitg.ArcgisRetry;

/**
 * Pages one SITG ArcGIS FeatureServer layer ({@code f=geojson}, {@code outSR=4326}, OBJECTID order, 1000 per
 * page) and hands each page to a consumer, never accumulating. Same conventions and the shared
 * {@link ArcgisRetry} as the dossier and parcel imports. Only the fields named in {@code outFields} are
 * requested, so non-open columns cannot slip in.
 */
class ArcgisLayerClient {

    private static final Logger log = LoggerFactory.getLogger(ArcgisLayerClient.class);

    static final int PAGE_SIZE = 1000;
    static final Duration TIMEOUT = Duration.ofSeconds(60);
    static final String BASE = "https://vector.sitg.ge.ch/arcgis/rest/services/";
    // 7 decimals of a degree = ~1 cm, like the parcels, so building and zone edges still meet parcel edges.
    private static final int GEOMETRY_PRECISION = 7;
    private static final DateTimeFormatter CURSOR_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final String name;
    private final String queryUrl;
    private final String outFields;
    private final boolean geometry;
    private final RestClient restClient;
    private final Consumer<Duration> sleeper;

    /** {@code layer} is the SITG service name, e.g. {@code CAD_BATIMENT_HORSOL}. */
    ArcgisLayerClient(String layer, String outFields, boolean geometry) {
        this(layer, BASE + layer + "/FeatureServer/0/query", outFields, geometry, TIMEOUT, ArcgisRetry::sleep);
    }

    /** For tests: another URL, a short timeout, and a sleeper that records the backoff. */
    ArcgisLayerClient(String name, String queryUrl, String outFields, boolean geometry, Duration timeout,
            Consumer<Duration> sleeper) {
        var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        this.name = name;
        this.queryUrl = queryUrl;
        this.outFields = outFields;
        this.geometry = geometry;
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.sleeper = sleeper;
    }

    static String timestampLiteral(Instant instant) {
        return "TIMESTAMP '" + CURSOR_FORMAT.format(instant) + "'";
    }

    /** Hands every feature matching {@code where} to {@code pageConsumer}, page by page; returns how many. */
    long fetch(String where, Consumer<List<LayerPage.Feature>> pageConsumer) {
        long total = 0;
        int offset = 0;
        for (int pageNo = 1; ; pageNo++) {
            LayerPage page = fetchPage(where, offset);
            int size = page.features().size();
            log.debug("{} page {} offset={} features={} exceededTransferLimit={}", name, pageNo, offset, size,
                    page.exceededTransferLimit());
            if (size > 0) {
                pageConsumer.accept(page.features());
            }
            total += size;
            offset += size;
            if (size == 0 || (size < PAGE_SIZE && !page.exceededTransferLimit())) {
                return total;
            }
        }
    }

    private LayerPage fetchPage(String where, int offset) {
        return ArcgisRetry.call("SITG " + name, offset, sleeper, () -> requestPage(where, offset));
    }

    private LayerPage requestPage(String where, int offset) {
        LayerPage page = restClient.get()
                .uri(queryUrl + "?where={where}&outFields={fields}&outSR=4326&geometryPrecision={precision}"
                                + "&returnGeometry={geometry}&f=geojson&resultOffset={offset}"
                                + "&resultRecordCount={count}&orderByFields={order}",
                        where, outFields, GEOMETRY_PRECISION, geometry, offset, PAGE_SIZE, "OBJECTID ASC")
                .retrieve()
                .body(LayerPage.class);
        if (page == null) {
            throw new IllegalStateException("SITG " + name + ": empty body at offset " + offset);
        }
        if (page.error() != null) {
            throw new IllegalStateException("SITG " + name + " query failed at offset " + offset + ": "
                    + page.error().code() + " " + page.error().message() + " " + page.error().details());
        }
        if (page.features() == null) {
            throw new IllegalStateException("SITG " + name + " response has no features array at offset " + offset);
        }
        return page;
    }
}
