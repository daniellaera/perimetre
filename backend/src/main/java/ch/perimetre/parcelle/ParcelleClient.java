package ch.perimetre.parcelle;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import ch.perimetre.sitg.ArcgisRetry;

/**
 * SITG layer CAD_PARCELLE_MENSU ("parcelle en vigueur"). Pages are handed to a consumer one by one, never
 * accumulated: the full layer is ~73k polygons (~160 MB of GeoJSON), more than the backend's heap could hold.
 */
@Component
class ParcelleClient {

    private static final Logger log = LoggerFactory.getLogger(ParcelleClient.class);

    private static final String QUERY_URL =
            "https://vector.sitg.ge.ch/arcgis/rest/services/CAD_PARCELLE_MENSU/FeatureServer/0/query";
    static final int PAGE_SIZE = 1000;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    // Only what the app needs; owner-related columns (TYPE_PROPRI, PROPRI_PUBLIQUE) are never requested.
    private static final String OUT_FIELDS = "EGRID,COMMUNE,NO_COMM,NO_PARCELLE,IDEDDP,SURFACE,DATEDT,LIEN_WWW";
    // 7 decimals of a degree = ~1 cm: far below the cadastre's accuracy, and a third less data.
    private static final int GEOMETRY_PRECISION = 7;
    private static final DateTimeFormatter CURSOR_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final String queryUrl;
    private final RestClient restClient;
    private final Consumer<Duration> sleeper;

    ParcelleClient() {
        this(QUERY_URL, TIMEOUT, ArcgisRetry::sleep);
    }

    /** For tests: another URL, a short timeout, and a sleeper that records the backoff. */
    ParcelleClient(String queryUrl, Duration timeout, Consumer<Duration> sleeper) {
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(timeout).build());
        requestFactory.setReadTimeout(timeout);
        this.queryUrl = queryUrl;
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
        this.sleeper = sleeper;
    }

    /**
     * Hands every parcel in force to {@code pageConsumer}, page by page; with a non-null {@code since} only
     * those whose last mutation {@code DATEDT >= since}. Returns the number of features fetched.
     */
    long fetch(Instant since, Consumer<java.util.List<ParcellePage.Feature>> pageConsumer) {
        String where = "VALIDITE = 'en vigueur'"
                + (since == null ? "" : " AND DATEDT >= TIMESTAMP '" + CURSOR_FORMAT.format(since) + "'");
        long total = 0;
        int offset = 0;
        for (int pageNo = 1; ; pageNo++) {
            ParcellePage page = fetchPage(where, offset);
            int size = page.features().size();
            log.debug("Parcel page {} offset={} features={} exceededTransferLimit={}",
                    pageNo, offset, size, page.exceededTransferLimit());
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

    private ParcellePage fetchPage(String where, int offset) {
        return ArcgisRetry.call("SITG parcels", offset, sleeper, () -> requestPage(where, offset));
    }

    private ParcellePage requestPage(String where, int offset) {
        ParcellePage page = restClient.get()
                .uri(queryUrl + "?where={where}&outFields={fields}&outSR=4326&geometryPrecision={precision}&f=geojson"
                                + "&resultOffset={offset}&resultRecordCount={count}&orderByFields={order}",
                        where, OUT_FIELDS, GEOMETRY_PRECISION, offset, PAGE_SIZE, "OBJECTID ASC")
                .retrieve()
                .body(ParcellePage.class);
        if (page == null) {
            throw new IllegalStateException("SITG parcels: empty body at offset " + offset);
        }
        if (page.error() != null) {
            throw new IllegalStateException("SITG parcels query failed at offset " + offset + ": "
                    + page.error().code() + " " + page.error().message() + " " + page.error().details());
        }
        if (page.features() == null) {
            throw new IllegalStateException("SITG parcels response has no features array at offset " + offset);
        }
        return page;
    }
}
