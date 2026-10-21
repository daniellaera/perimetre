package ch.perimetre.sitg;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

@Component
class SitgClient {

    private static final Logger log = LoggerFactory.getLogger(SitgClient.class);

    private static final String QUERY_URL =
            "https://vector.sitg.ge.ch/arcgis/rest/services/Hosted/SIT_AUTOR_DOSSIER/FeatureServer/0/query";
    private static final int PAGE_SIZE = 1000;
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final DateTimeFormatter CURSOR_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final RestClient restClient;

    SitgClient() {
        var requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
        requestFactory.setReadTimeout(TIMEOUT);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /** Fetches every dossier with {@code date_maj_2 >= cursor} and a known deposit date. */
    List<SitgPage.Feature> fetchModifiedSince(Instant cursor) {
        String where = "date_maj_2 >= TIMESTAMP '" + CURSOR_FORMAT.format(cursor) + "' AND date_depot IS NOT NULL";
        List<SitgPage.Feature> features = new ArrayList<>();
        int offset = 0;
        for (int pageNo = 1; ; pageNo++) {
            SitgPage page = fetchPage(where, offset);
            int size = page.features().size();
            log.info("SITG page {} offset={} features={} exceededTransferLimit={}",
                    pageNo, offset, size, page.exceededTransferLimit());
            features.addAll(page.features());
            offset += size;
            if (size == 0 || (size < PAGE_SIZE && !page.exceededTransferLimit())) {
                return features;
            }
        }
    }

    private SitgPage fetchPage(String where, int offset) {
        try {
            return requestPage(where, offset);
        } catch (HttpServerErrorException e) {
            log.warn("SITG returned {} at offset {}, retrying once", e.getStatusCode(), offset);
            return requestPage(where, offset);
        }
    }

    private SitgPage requestPage(String where, int offset) {
        SitgPage page = restClient.get()
                .uri(QUERY_URL + "?where={where}&outFields=*&outSR=4326&f=geojson"
                                + "&resultOffset={offset}&resultRecordCount={count}&orderByFields={order}",
                        where, offset, PAGE_SIZE, "objectid ASC")
                .retrieve()
                .body(SitgPage.class);
        if (page == null) {
            throw new IllegalStateException("SITG returned an empty body at offset " + offset);
        }
        if (page.error() != null) {
            throw new IllegalStateException("SITG query failed at offset " + offset + ": "
                    + page.error().code() + " " + page.error().message() + " " + page.error().details());
        }
        if (page.features() == null) {
            throw new IllegalStateException("SITG response has no features array at offset " + offset);
        }
        return page;
    }
}
