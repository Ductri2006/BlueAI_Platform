package com.blueai.product;

import com.blueai.auth.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import java.net.URI;
import java.util.*;

@Component
public class BluecomProductClient {
    private final ProductProperties properties;
    private final ObjectMapper exactJson = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public BluecomProductClient(ProductProperties properties) { this.properties = properties; }
    public List<JsonNode> fetchAll() {
        RestClient client = configuredClient();
        List<JsonNode> products = new ArrayList<>();
        Set<String> skus = new HashSet<>();
        int expected = -1, pages = 1;
        try {
            for (int page = 1; page <= pages; page++) {
                JsonNode result = parse(client.get().uri("/esim/packages?page={page}&size=200", page)
                        .header("apiKey", properties.getApiKey()).retrieve().body(String.class));
                if (!validPage(result, page, 200)) throw invalidResponse();
                int count = result.path("count").intValue(), total = result.path("totalPages").intValue();
                if (page == 1) {
                    expected = count; pages = total;
                    if (expected < 0 || expected > 100_000 || pages < (expected == 0 ? 0 : 1) || pages > 500
                            || pages != (expected + 199) / 200) throw invalidResponse();
                } else if (count != expected || total != pages) throw invalidResponse();
                for (JsonNode product : result.path("packageList")) {
                    if (!skus.add(product.path("sku").asText("").toLowerCase(Locale.ROOT))) throw invalidResponse();
                    products.add(product);
                }
                int expectedPageSize = Math.min(200, expected - (page - 1) * 200);
                if (result.path("packageList").size() != expectedPageSize) throw invalidResponse();
            }
        } catch (RestClientException ex) {
            throw upstreamFailure(ex);
        }
        if (products.size() != expected) throw invalidResponse();
        validateRecords(products);
        return List.copyOf(products);
    }
    public JsonNode fetchPage(int page, int size) {
        if (page < 1 || page > 500 || size < 1 || size > 200)
            throw new ApiException(HttpStatus.BAD_REQUEST,"PRODUCT_PAGE_INVALID","Trang phải từ 1 đến 500; kích thước từ 1 đến 200.");
        RestClient client = configuredClient();
        try {
            JsonNode result = parse(client.get().uri("/esim/packages?page={page}&size={size}",page,size)
                    .header("apiKey",properties.getApiKey()).retrieve().body(String.class));
            if (!validPage(result,page,size)) throw invalidResponse();
            long expectedSize = Math.min(size,Math.max(0,result.path("count").asLong()-(long)(page-1)*size));
            if (result.path("packageList").size() != expectedSize) throw invalidResponse();
            List<JsonNode> records = new ArrayList<>();
            result.path("packageList").forEach(records::add);
            validateRecords(records);
            return result;
        } catch (RestClientException ex) { throw upstreamFailure(ex); }
    }
    private ApiException upstreamFailure(RestClientException ex) {
        // Never expose upstream response bodies or headers, including credential diagnostics.
        if (ex instanceof RestClientResponseException response) {
            if (response.getStatusCode().value() == 403)
                return new ApiException(HttpStatus.BAD_GATEWAY,"PRODUCT_UPSTREAM_IP_FORBIDDEN",
                        "Bluecom từ chối truy cập. Kiểm tra IP public đi ra của máy chủ đã được đăng ký trong allowlist.");
            if (response.getStatusCode().value() == 401)
                return new ApiException(HttpStatus.BAD_GATEWAY,"PRODUCT_UPSTREAM_UNAUTHORIZED",
                        "Bluecom không chấp nhận API key. Kiểm tra cấu hình API key trên máy chủ.");
        }
        return new ApiException(HttpStatus.BAD_GATEWAY,"PRODUCT_SYNC_FAILED",
                "Không thể lấy danh mục Bluecom. Kiểm tra API key, IP được phép và kết nối máy chủ.");
    }
    private RestClient configuredClient() {
        if (!properties.configured()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "PRODUCTS_NOT_CONFIGURED", "Chưa cấu hình BLUECOM_PRODUCTS_API_KEY trên máy chủ.");
        URI base;
        try { base = URI.create(properties.getBaseUrl()); }
        catch (IllegalArgumentException ex) { throw invalidConfiguration(); }
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null || base.getRawUserInfo() != null
                || base.getRawQuery() != null || base.getRawFragment() != null) throw invalidConfiguration();
        return createClient(base);
    }
    protected RestClient createClient(URI base) {
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(java.net.HttpURLConnection connection, String method) throws java.io.IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(properties.getTimeout());
        factory.setReadTimeout(properties.getTimeout());
        return RestClient.builder().requestFactory(factory).baseUrl(base.toString().replaceAll("/+$", "")).build();
    }
    private ApiException invalidConfiguration() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PRODUCTS_URL_INVALID", "URL API sản phẩm phải là HTTPS hợp lệ.");
    }
    private ApiException invalidResponse() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "PRODUCT_CATALOG_INCOMPLETE",
                "Danh mục Bluecom không đầy đủ hoặc đã thay đổi trong lúc tải. Danh mục hiện tại được giữ nguyên; hãy đồng bộ lại.");
    }
    private JsonNode parse(String body) {
        if (body == null || body.length() > 8_000_000) throw invalidResponse();
        try { return exactJson.readTree(body); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw invalidResponse(); }
    }
    private boolean validPage(JsonNode result, int page, int size) {
        if (result == null || !result.isObject() || !result.path("packageList").isArray()) return false;
        for (String key : List.of("count","page","size","totalPages"))
            if (!result.path(key).isIntegralNumber() || !result.path(key).canConvertToInt()) return false;
        int count = result.path("count").intValue();
        return count >= 0 && count <= 100_000 && result.path("page").intValue() == page
                && result.path("size").intValue() == size && result.path("totalPages").intValue() == (count+size-1)/size;
    }
    static void validateRecords(List<JsonNode> products) {
        if (products == null || products.size() > 100_000) throw invalidRecords();
        Set<String> seen = new HashSet<>();
        for (JsonNode product : products) {
            if (product == null || !product.isObject() || !product.path("sku").isTextual()
                    || !product.path("sku").textValue().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
                    || !product.path("name").isTextual() || product.path("name").textValue().isBlank()
                    || product.path("name").textValue().length() > 500
                    || product.path("name").textValue().codePoints().anyMatch(Character::isISOControl)
                    || !seen.add(product.path("sku").textValue().toLowerCase(Locale.ROOT))) throw invalidRecords();
            for (String field : List.of("price","sellingPrice")) {
                JsonNode amount = product.path(field);
                if (amount.isMissingNode() || amount.isNull()) continue;
                if (!amount.isNumber()) throw invalidRecords();
                var decimal = amount.decimalValue().stripTrailingZeros();
                if (decimal.signum() < 0 || decimal.scale() > 4 || decimal.precision()-decimal.scale() > 15)
                    throw invalidRecords();
            }
            JsonNode extra = product.path("extraData");
            if (!extra.isMissingNode() && !extra.isNull() && !extra.isObject()) throw invalidRecords();
        }
    }
    private static ApiException invalidRecords() {
        return new ApiException(HttpStatus.BAD_GATEWAY,"PRODUCT_CATALOG_INCOMPLETE",
                "Dữ liệu sản phẩm đối tác không hợp lệ; giữ nguyên bản danh mục trước.");
    }
}
