package com.blueai.product;

import com.blueai.auth.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
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
    public BluecomProductClient(ProductProperties properties) { this.properties = properties; }
    public List<JsonNode> fetchAll() {
        RestClient client = configuredClient();
        List<JsonNode> products = new ArrayList<>();
        Set<String> skus = new HashSet<>();
        int expected = -1, pages = 1;
        try {
            for (int page = 1; page <= pages; page++) {
                JsonNode result = client.get().uri("/esim/packages?page={page}&size=200", page)
                        .header("apiKey", properties.getApiKey()).retrieve().body(JsonNode.class);
                if (result == null || !result.path("packageList").isArray() || !result.path("count").isIntegralNumber()
                        || result.path("page").asInt(-1) != page || result.path("size").asInt(-1) != 200) throw invalidResponse();
                int count = result.path("count").asInt(-1), total = result.path("totalPages").asInt(-1);
                if (page == 1) {
                    expected = count; pages = total;
                    if (expected < 0 || expected > 100_000 || pages < (expected == 0 ? 0 : 1) || pages > 500
                            || pages != (expected + 199) / 200) throw invalidResponse();
                } else if (count != expected || total != pages) throw invalidResponse();
                for (JsonNode product : result.path("packageList")) {
                    String sku = product.path("sku").asText("");
                    if (sku.isBlank() || sku.length() > 160 || product.path("name").asText("").isBlank()
                            || !product.path("sellingPrice").isNumber() || product.path("sellingPrice").decimalValue().signum() < 0
                            || !skus.add(sku)) throw invalidResponse();
                    products.add(product);
                }
                int expectedPageSize = Math.min(200, expected - (page - 1) * 200);
                if (result.path("packageList").size() != expectedPageSize) throw invalidResponse();
            }
        } catch (RestClientException ex) {
            throw upstreamFailure(ex);
        }
        if (products.size() != expected) throw invalidResponse();
        return List.copyOf(products);
    }
    public JsonNode fetchPage(int page, int size) {
        if (page < 1 || page > 500 || size < 1 || size > 200)
            throw new ApiException(HttpStatus.BAD_REQUEST,"PRODUCT_PAGE_INVALID","Trang phải từ 1 đến 500; kích thước từ 1 đến 200.");
        RestClient client = configuredClient();
        try {
            JsonNode result = client.get().uri("/esim/packages?page={page}&size={size}",page,size)
                    .header("apiKey",properties.getApiKey()).retrieve().body(JsonNode.class);
            if (result == null || !result.path("packageList").isArray()
                    || !result.path("count").isIntegralNumber() || result.path("count").asLong(-1) < 0
                    || result.path("page").asInt(-1) != page || result.path("size").asInt(-1) != size
                    || result.path("totalPages").asLong(-1) != (result.path("count").asLong()+size-1)/size)
                throw invalidResponse();
            long expectedSize = Math.min(size,Math.max(0,result.path("count").asLong()-(long)(page-1)*size));
            if (result.path("packageList").size() != expectedSize) throw invalidResponse();
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
        var factory = new SimpleClientHttpRequestFactory();
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
}
