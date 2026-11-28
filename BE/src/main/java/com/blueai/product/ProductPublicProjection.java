package com.blueai.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;

/** Construct a new tree at every boundary. Never copy an entire supplier object or nested policy object. */
final class ProductPublicProjection {
    private static final Set<String> ISO_COUNTRIES = Set.of(Locale.getISOCountries());
    private ProductPublicProjection() { }

    static ObjectNode project(ObjectMapper mapper, JsonNode input) {
        ObjectNode output = mapper.createObjectNode();
        for (String field : List.of("sku", "name", "model", "regional", "imageUrl"))
            copyText(input, output, field, 500);
        if (input.path("sellingPrice").isNumber()) output.set("sellingPrice", input.get("sellingPrice").deepCopy());
        String currency = currency(input.path("currencyCode"));
        if (currency != null) output.put("currencyCode", currency);
        // Unreviewed free-form descriptions/policies may contain supplier-only prices, including encoded JSON.
        ObjectNode extra = output.putObject("extraData");
        JsonNode rawExtra = input.path("extraData");
        for (String field : List.of("volumeUnit", "dataUnit", "dataPeriod", "durationUnit"))
            copyText(rawExtra, extra, field, 1000);
        for (String field : List.of("volume", "dataType", "supportTopUpType")) {
            JsonNode value = rawExtra.path(field);
            if (value.isTextual() && value.textValue().length() <= 100) extra.put(field, value.textValue());
            else if (value.isNumber()) extra.set(field, value.deepCopy());
        }
        String speed = text(rawExtra.path("speed"));
        if (speed != null && speed.matches("[2-5]G(?:/[2-5]G)*")) extra.put("speed",speed);
        if (rawExtra.path("duration").isIntegralNumber() && rawExtra.path("duration").canConvertToInt()
                && rawExtra.path("duration").intValue() > 0 && rawExtra.path("duration").intValue() <= 3650)
            extra.put("duration", rawExtra.path("duration").intValue());
        Set<String> codes = countries(rawExtra);
        extra.put("location", String.join(",", codes));
        var coverages = extra.putObject("operator").putArray("coverages");
        for (JsonNode raw : rawExtra.path("operator").path("coverages")) {
            String code = text(raw.path("locationCode"));
            if (code == null || !ISO_COUNTRIES.contains(code.toUpperCase(Locale.ROOT))) continue;
            ObjectNode coverage = coverages.addObject().put("locationCode", code.toUpperCase(Locale.ROOT));
            copyText(raw, coverage, "locationName", 200);
            // Only a public operator label is retained, never arbitrary nested supplier/operator metadata.
            copyText(raw, coverage, "operatorName", 200);
        }
        return output;
    }

    static Set<String> countries(JsonNode extra) {
        Set<String> codes = new TreeSet<>();
        String location = text(extra.path("location"));
        if (location != null) for (String raw : location.split(",")) {
            String code = raw.strip().toUpperCase(Locale.ROOT);
            if (ISO_COUNTRIES.contains(code)) codes.add(code);
        }
        for (JsonNode coverage : extra.path("operator").path("coverages")) {
            String value = text(coverage.path("locationCode"));
            if (value != null && ISO_COUNTRIES.contains(value.toUpperCase(Locale.ROOT)))
                codes.add(value.toUpperCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(codes);
    }

    static String currency(JsonNode value) {
        String code = text(value);
        if (code == null || !code.matches("[A-Z]{3}")) return null;
        try { Currency.getInstance(code); return code; }
        catch (IllegalArgumentException invalid) { return null; }
    }

    static String text(JsonNode node) { return node.isTextual() ? node.textValue() : null; }

    private static void copyText(JsonNode from, ObjectNode to, String field, int max) {
        String value = text(from.path(field));
        if (value != null && value.length() <= max) to.put(field, value);
    }
}
