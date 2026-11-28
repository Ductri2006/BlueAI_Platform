package com.blueai.product;

import com.blueai.auth.exception.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.URI;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class BluecomProductClientContractTest {
    private final ProductProperties properties = new ProductProperties();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final BluecomProductClient client = new BluecomProductClient(properties) {
        @Override protected RestClient createClient(URI base) { return builder.baseUrl(base.toString()).build(); }
    };
    private void responds(String json) {
        properties.setApiKey("synthetic-partner-key");
        server.expect(anything()).andRespond(withSuccess(json,MediaType.APPLICATION_JSON));
    }
    @Test void partnerSkuWithInternalCountryLabelSpaceIsPreservedVerbatim() {
        String sku="BLU-ESIM-04-SOUTH -1D-1024-6F34C139";
        responds("{\"count\":1,\"page\":1,\"size\":200,\"totalPages\":1,\"packageList\":[{\"sku\":\""+sku+"\",\"name\":\"Country daily plan\",\"sellingPrice\":22000}]}");
        assertThat(client.fetchAll().getFirst().path("sku").asText()).isEqualTo(sku);
        server.verify();
    }
    @Test void exactDecimalPriceSurvivesHttpParsingWithoutRoundingOrCurrencyInference() {
        responds("""
                {"count":1,"page":1,"size":200,"totalPages":1,"packageList":[
                {"sku":"EXACT","name":"Exact decimal","sellingPrice":999999999999999.9999,"currencyCode":"USD"}]}
                """);
        var item = client.fetchAll().getFirst();
        assertThat(item.path("sellingPrice").decimalValue().toPlainString()).isEqualTo("999999999999999.9999");
        assertThat(item.path("currencyCode").asText()).isEqualTo("USD");
        server.verify();
    }
    @ParameterizedTest @ValueSource(strings={
            "{\"count\":4294967297,\"page\":1,\"size\":200,\"totalPages\":1,\"packageList\":[{\"sku\":\"A\",\"name\":\"A\",\"sellingPrice\":1}]}",
            "{\"count\":1,\"page\":\"1\",\"size\":200,\"totalPages\":1,\"packageList\":[{\"sku\":\"A\",\"name\":\"A\",\"sellingPrice\":1}]}",
            "{\"count\":1,\"page\":1,\"size\":200,\"totalPages\":1,\"packageList\":[]}",
            "{\"count\":2,\"page\":1,\"size\":200,\"totalPages\":1,\"packageList\":[{\"sku\":\"SAME\",\"name\":\"A\",\"sellingPrice\":1},{\"sku\":\"same\",\"name\":\"B\",\"sellingPrice\":1}]}",
            "{\"count\":1,\"page\":1,\"size\":200,\"totalPages\":1,\"packageList\":[{\"sku\":\"A\",\"name\":\"A\",\"sellingPrice\":-1}]}",
            "{\"count\":1,\"page\":1,\"size\":200,\"totalPages\":1,\"packageList\":[{\"sku\":\"A\",\"name\":\"A\",\"sellingPrice\":0.12345}]}",
            "{\"count\":1,\"count\":0,\"page\":1,\"size\":200,\"totalPages\":0,\"packageList\":[]}"
    })
    void malformedOverflowedDuplicateAndIncompleteResponsesAreRejected(String response) {
        responds(response);
        assertThatThrownBy(client::fetchAll).isInstanceOf(ApiException.class)
                .extracting("code").isEqualTo("PRODUCT_CATALOG_INCOMPLETE");
        server.verify();
    }
    @Test void nullPriceAndMissingCurrencyRemainUnknownRatherThanZeroVnd() {
        responds("""
                {"count":1,"page":1,"size":200,"totalPages":1,"packageList":[
                {"sku":"UNKNOWN","name":"Unknown facts","sellingPrice":null}]}
                """);
        var item = client.fetchAll().getFirst();
        assertThat(item.path("sellingPrice").isNull()).isTrue();
        assertThat(item.path("currencyCode").isMissingNode()).isTrue();
    }
    @Test void actualAllowlistFailureShapeIsRedactedAndNotRetried() {
        properties.setApiKey("synthetic-partner-key");
        server.expect(anything()).andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
                .body("[{\"name\":\"IP\",\"code\":\"FORBIDDEN\",\"message\":\"Caller IP is not on reseller allowlist\"}]"));
        assertThatThrownBy(client::fetchAll).isInstanceOf(ApiException.class)
                .hasMessageNotContaining("Caller IP").hasMessageNotContaining("synthetic-partner-key")
                .extracting("code").isEqualTo("PRODUCT_UPSTREAM_IP_FORBIDDEN");
        server.verify();
    }
    @Test void rateLimitIsOneRequestAndRetainsNoPartnerDiagnostics() {
        properties.setApiKey("synthetic-partner-key");
        server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("private-key"));
        assertThatThrownBy(client::fetchAll).isInstanceOf(ApiException.class).hasMessageNotContaining("private-key");
        server.verify();
    }
}
