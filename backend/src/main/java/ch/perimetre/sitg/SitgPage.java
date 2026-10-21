package ch.perimetre.sitg;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * One page of the SITG ArcGIS query in {@code f=geojson} format. ArcGIS reports query errors
 * with HTTP 200 and an {@code error} object instead of features.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record SitgPage(List<Feature> features, PageProperties properties, Error error) {

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
    record Feature(Geometry geometry, Attributes properties) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Geometry(String type, List<Double> coordinates) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record Attributes(
            String idDossier,
            String typeDossier,
            String typeOperation,
            String operation,
            String description,
            String statut,
            Long dateDepot,
            @JsonProperty("date_maj_2") Long dateMaj2,
            String lienSad) {
    }
}
