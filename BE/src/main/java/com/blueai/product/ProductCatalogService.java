package com.blueai.product;

import com.blueai.auth.exception.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class ProductCatalogService {
    private final ProductRepository repository;
    private final BluecomProductClient client;
    private final ProductProperties properties;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final ReentrantLock syncLock = new ReentrantLock();
    public ProductCatalogService(ProductRepository repository, BluecomProductClient client, ProductProperties properties,
                                 ObjectMapper mapper, org.springframework.transaction.PlatformTransactionManager manager) {
        this.repository = repository; this.client = client; this.properties = properties;
        this.mapper = mapper; transaction = new TransactionTemplate(manager);
    }
    public record Summary(long available, Instant lastSyncedAt, boolean configured, String source, boolean syncing) { }
    public record PageResult(List<JsonNode> items, long total, int page, int size, int totalPages) { }
    public Summary summary() {
        Product latest = repository.findFirstByOrderBySyncedAtDesc();
        return new Summary(repository.countByAvailableTrue(), latest == null ? null : latest.getSyncedAt(),
                properties.configured(), properties.getBaseUrl(), syncLock.isLocked());
    }
    public Summary sync() {
        if (!syncLock.tryLock()) throw new ApiException(HttpStatus.CONFLICT, "PRODUCT_SYNC_RUNNING", "Đang đồng bộ danh mục sản phẩm.");
        try {
            // Complete and validate ALL pages before entering the database transaction.
            List<JsonNode> incoming = client.fetchAll();
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            transaction.executeWithoutResult(status -> {
                Map<String, Product> existing = new HashMap<>();
                repository.findAll().forEach(product -> existing.put(product.getSku(), product));
                List<Product> products = new ArrayList<>();
                for (JsonNode item : incoming) {
                    JsonNode extra = item.path("extraData");
                    Set<String> countries = new TreeSet<>();
                    StringBuilder search = new StringBuilder(item.path("sku").asText()).append(' ')
                            .append(item.path("name").asText()).append(' ').append(item.path("regional").asText()).append(' ')
                            .append(item.path("description").asText());
                    for (String code : extra.path("location").asText("").split(",")) if (!code.isBlank()) countries.add(code.trim().toUpperCase(Locale.ROOT));
                    for (JsonNode coverage : extra.path("operator").path("coverages")) {
                        countries.add(coverage.path("locationCode").asText("").toUpperCase(Locale.ROOT));
                        search.append(' ').append(coverage.path("locationName").asText(""));
                    }
                    countries.remove("");
                    for (String code : countries) if (code.length() == 2) {
                        Locale location = new Locale.Builder().setRegion(code).build();
                        search.append(' ').append(location.getDisplayCountry(Locale.ENGLISH))
                                .append(' ').append(location.getDisplayCountry(Locale.forLanguageTag("vi")));
                    }
                    Product product = existing.getOrDefault(item.path("sku").asText(), new Product(item.path("sku").asText()));
                    product.update(item.path("name").asText(), item.path("regional").asText(null), decimal(item,"price"),
                            decimal(item,"sellingPrice"), item.path("currencyCode").asText("VND"),
                            extra.path("duration").canConvertToInt() ? extra.path("duration").intValue() : null,
                            extra.path("volume").asText(null), item.toString(), normalize(search.toString()),
                            "|" + String.join("|", countries) + "|", now);
                    products.add(product);
                }
                repository.saveAllAndFlush(products);
                repository.deactivateOlder(now);
            });
        } finally { syncLock.unlock(); }
        return summary();
    }
    public PageResult search(String query, String country, Integer days, BigDecimal budget, int page, int size, boolean admin) {
        int limit = Math.max(1, Math.min(100, size));
        Specification<Product> spec = (root, criteria, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.isTrue(root.get("available")));
            if (country != null && !country.isBlank()) predicates.add(cb.like(root.get("locationCodes"), "%|" + country.toUpperCase(Locale.ROOT) + "|%"));
            if (days != null) predicates.add(cb.greaterThanOrEqualTo(root.get("durationDays"), days));
            if (budget != null) {
                predicates.add(cb.equal(root.get("currencyCode"), "VND"));
                predicates.add(cb.lessThanOrEqualTo(root.get("sellingPrice"), budget));
            }
            if (query != null && !query.isBlank()) {
                String term = normalize(query).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
                predicates.add(cb.like(root.get("searchText"), "%" + term + "%", '\\'));
            }
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
        Page<Product> found = repository.findAll(spec, PageRequest.of(Math.max(0,page),limit,Sort.by("sellingPrice").ascending().and(Sort.by("sku"))));
        return new PageResult(found.stream().map(product -> view(product,admin)).toList(),found.getTotalElements(),found.getNumber(),limit,found.getTotalPages());
    }
    public JsonNode detail(String sku, boolean admin) {
        return view(repository.findById(sku).filter(Product::isAvailable).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND,"PRODUCT_NOT_FOUND","Không tìm thấy sản phẩm đang bán.")),admin);
    }
    private JsonNode view(Product product, boolean admin) {
        try {
            ObjectNode data = (ObjectNode)mapper.readTree(product.getPayload());
            if (!admin) {
                ObjectNode safe = mapper.createObjectNode();
                for (String key : List.of("sku", "name", "model", "regional", "description", "typePackage",
                        "sellingPrice", "currencyCode", "imageUrl", "periodNum", "extraData", "hotFlag"))
                    if (data.has(key)) safe.set(key, data.get(key));
                data = safe;
            }
            data.put("syncedAt",product.getSyncedAt().toString());
            data.put("available",product.isAvailable());
            return data;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException("Invalid stored product",ex); }
    }
    private BigDecimal decimal(JsonNode item,String field) { return item.path(field).isNumber() ? item.path(field).decimalValue() : null; }
    public static String normalize(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('đ','d').replace('Đ','D').toLowerCase(Locale.ROOT);
    }
}
