package com.blueai.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProductPublicProjectionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void nestedSupplierPricesCannotEnterPublicJsonOrPolicies() throws Exception {
        var raw = mapper.readTree("""
                {"sku":"SAFE","name":"Japan","sellingPrice":19.95,"currencyCode":"USD","price":12345,
                 "description":"{\\"en\\":\\"Public\\",\\"costPrice\\":12345}",
                 "extraData":{"location":"JP","volume":{"value":1024,"costPrice":12345},
                  "wholesalePrice":12345,"refundPolicy":{"text":"refund","price":12345},
                  "fupPolicy":"{\\"costPrice\\":12345}",
                  "operator":{"coverages":[{"locationCode":"JP","operatorName":"Public operator","costPrice":12345,"secret":{"price":12345}}]}}}
                """);
        var safe = ProductPublicProjection.project(mapper,raw);
        assertThat(safe.toString()).contains("SAFE","Public operator","19.95","USD")
                .doesNotContain("12345","costPrice","wholesalePrice","refundPolicy","description");
        assertThat(safe.path("extraData").path("volume").isMissingNode()).isTrue();
        assertThat(raw.path("price").asInt()).isEqualTo(12345);
    }
    @Test void onlyVerifiedCountryCodesAreCoverageAndMissingCurrencyStaysMissing() throws Exception {
        var safe = ProductPublicProjection.project(mapper,mapper.readTree("""
                {"sku":"GLOBAL","name":"Global","sellingPrice":0,"extraData":{"location":"!GL,JP,XX,%,SG","operator":{"coverages":[{"locationCode":"!RG"}]}}}
                """));
        assertThat(safe.path("currencyCode").isMissingNode()).isTrue();
        assertThat(safe.path("extraData").path("location").asText()).isEqualTo("JP,SG");
        assertThat(safe.path("sellingPrice").asInt()).isZero();
    }
}
