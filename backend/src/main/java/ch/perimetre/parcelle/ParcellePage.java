package ch.perimetre.parcelle;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;

/**
 * One page of the CAD_PARCELLE_MENSU ArcGIS query in {@code f=geojson} format. Only open attributes are
 * read (PROPRI_PUBLIQUE and the other owner-related columns are not even requested; TYPE_PROPRI is only the
 * 8-value category of the parcel). The
 * geometry stays a raw JSON tree: PostGIS parses it ({@code ST_GeomFromGeoJSON}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record ParcellePage(List<Feature> features, PageProperties properties, Error error) {

    boolean exceededTransferLimit() {
        return properties != null && properties.exceededTransferLimit();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PageProperties(boolean exceededTransferLimit) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Error(int code, String message, List<String> details) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Feature(JsonNode geometry, Attributes properties) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Attributes(
            @JsonProperty("EGRID") String egrid,
            @JsonProperty("COMMUNE") String commune,
            @JsonProperty("NO_COMM") Integer noComm,
            @JsonProperty("NO_PARCELLE") Integer noParcelle,
            @JsonProperty("IDEDDP") String ideddp,
            @JsonProperty("SURFACE") Integer surface,
            @JsonProperty("DATEDT") Long datedt,
            @JsonProperty("LIEN_WWW") String lienWww,
            @JsonProperty("TYPE_PROPRI") String typePropri,
            @JsonProperty("PLAN_RF") String planRf) {
    }
}
