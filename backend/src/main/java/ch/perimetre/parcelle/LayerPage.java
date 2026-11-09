package ch.perimetre.parcelle;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.JsonNode;

/**
 * One page of any SITG ArcGIS layer in {@code f=geojson}: the properties stay a raw tree, each importer reads
 * the open attributes it asked for. {@code geometry} is null when the query asked for none.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record LayerPage(List<Feature> features, PageProperties properties, Error error) {

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
    record Feature(JsonNode geometry, JsonNode properties) {

        boolean hasGeometry() {
            return geometry != null && !geometry.isNull() && !geometry.isMissingNode();
        }

        String text(String field) {
            JsonNode n = properties == null ? null : properties.get(field);
            if (n == null || n.isNull()) {
                return null;
            }
            String s = n.asString().strip();
            return s.isEmpty() || "<Nul>".equals(s) ? null : s;
        }

        Long number(String field) {
            JsonNode n = properties == null ? null : properties.get(field);
            return n != null && n.isNumber() ? n.asLong() : null;
        }

        Integer integer(String field) {
            Long l = number(field);
            return l == null ? null : Math.toIntExact(l);
        }
    }
}
