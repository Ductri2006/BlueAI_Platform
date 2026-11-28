package com.blueai.product;

import com.blueai.auth.exception.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.net.URI;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class BluecomProductClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProductProperties properties = new ProductProperties();
    private final RestClient.Builder builder = RestClient.builder();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final BluecomProductClient client = new BluecomProductClient(properties) {
        @Override protected RestClient createClient(URI base) { return builder.baseUrl(base.toString()).build(); }
    };
    private ObjectNode page(int number, int count, int start, int length) {
        ObjectNode result = mapper.createObjectNode().put("page",number).put("count",count).put("size",200).put("totalPages",(count+199)/200);
        ArrayNode items = result.putArray("packageList");
        for(int i=start;i<start+length;i++) items.addObject().put("sku","BLU-ESIM-"+i).put("name","Plan "+i).put("sellingPrice",20_000);
        return result;
    }
    @Test void fetchesOneLivePageWithPaginationMetadata() {
        properties.setApiKey("test-partner-secret");
        var response = page(2,3,2,1).put("size",2).put("totalPages",2);
        server.expect(requestTo(properties.getBaseUrl()+"/esim/packages?page=2&size=2"))
                .andExpect(header("apiKey","test-partner-secret"))
                .andRespond(withSuccess(response.toString(),MediaType.APPLICATION_JSON));
        assertThat(client.fetchPage(2,2)).isEqualTo(response);
        server.verify();
    }
    @Test void exposesSafeIpAllowlistErrorWithoutPartnerDiagnostics() {
        properties.setApiKey("partner-secret");
        server.expect(anything()).andRespond(withStatus(HttpStatus.FORBIDDEN).body("private partner-secret"));
        assertThatThrownBy(()->client.fetchPage(1,200)).isInstanceOf(ApiException.class)
                .hasMessageNotContaining("partner-secret").extracting("code").isEqualTo("PRODUCT_UPSTREAM_IP_FORBIDDEN");
    }
    @Test void rejectsInvalidLivePageAndBadKeySafely() {
        assertThatThrownBy(()->client.fetchPage(0,200)).isInstanceOf(ApiException.class).extracting("code").isEqualTo("PRODUCT_PAGE_INVALID");
        properties.setApiKey("partner-secret");
        server.expect(anything()).andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("private partner-secret"));
        assertThatThrownBy(()->client.fetchPage(1,200)).isInstanceOf(ApiException.class)
                .hasMessageNotContaining("partner-secret").extracting("code").isEqualTo("PRODUCT_UPSTREAM_UNAUTHORIZED");
    }
    @Test void fetchesEveryPageAndSendsKeyOnlyInHeader() {
        properties.setApiKey("test-partner-secret");
        server.expect(requestTo(properties.getBaseUrl()+"/esim/packages?page=1&size=200"))
                .andExpect(header("apiKey","test-partner-secret")).andRespond(withSuccess(page(1,201,0,200).toString(),MediaType.APPLICATION_JSON));
        server.expect(requestTo(properties.getBaseUrl()+"/esim/packages?page=2&size=200"))
                .andRespond(withSuccess(page(2,201,200,1).toString(),MediaType.APPLICATION_JSON));
        assertThat(client.fetchAll()).hasSize(201); server.verify();
    }
    @Test void rejectsDuplicateOrIncompleteCatalogue() {
        properties.setApiKey("test-key");
        server.expect(anything()).andRespond(withSuccess(page(1,201,0,200).toString(),MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess(page(2,201,0,1).toString(),MediaType.APPLICATION_JSON));
        assertThatThrownBy(client::fetchAll).isInstanceOf(ApiException.class).extracting("code").isEqualTo("PRODUCT_CATALOG_INCOMPLETE");
    }
    @Test void redactsUpstreamErrorsAndDoesNotFollowPaginationLinks() {
        properties.setApiKey("partner-secret");
        server.expect(anything()).andRespond(withStatus(HttpStatus.FORBIDDEN).body("private diagnostics partner-secret"));
        assertThatThrownBy(client::fetchAll).isInstanceOf(ApiException.class).hasMessageNotContaining("partner-secret");
    }
    @Test void rejectsUnconfiguredAndInsecureHostBeforeNetworkCall() {
        assertThatThrownBy(client::fetchAll).isInstanceOf(ApiException.class).extracting("code").isEqualTo("PRODUCTS_NOT_CONFIGURED");
        properties.setApiKey("secret"); properties.setBaseUrl("http://unsafe.test");
        assertThatThrownBy(client::fetchAll).isInstanceOf(ApiException.class).extracting("code").isEqualTo("PRODUCTS_URL_INVALID");
    }
}
