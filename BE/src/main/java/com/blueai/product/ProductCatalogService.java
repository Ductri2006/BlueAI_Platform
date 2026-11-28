package com.blueai.product;

import com.blueai.auth.exception.ApiException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
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
        this.mapper = mapper.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        transaction = new TransactionTemplate(manager);
    }
    public record Summary(long available, Instant lastSyncedAt, boolean configured, String source, boolean syncing) { }
    public record PageResult(List<JsonNode> items, long total, int page, int size, int totalPages) {
        public PageResult { items = List.copyOf(items); }
    }
    public enum Availability { READY, UNAVAILABLE, STALE }
    public record AdviceSnapshot(Availability availability, List<ProductFacts> facts) {
        public AdviceSnapshot { facts = List.copyOf(facts); }
    }
    public record Budget(BigDecimal amount, String currencyCode) { }

    public Summary summary() {
        Product latest = currentSourceLatest();
        long count = latest == null ? 0 : repository.count((root, query, cb) -> cb.and(
                cb.isTrue(root.get("available")), cb.equal(root.get("snapshotId"), latest.getSnapshotId())));
        return new Summary(count, latest == null ? null : latest.getSyncedAt(), properties.configured(),
                latest == null ? null : latest.getSourceBaseUrl(), syncLock.isLocked());
    }
    public Summary sync() {
        if (!syncLock.tryLock()) throw new ApiException(HttpStatus.CONFLICT, "PRODUCT_SYNC_RUNNING", "Đang đồng bộ danh mục sản phẩm.");
        try {
            String source = properties.sourceBaseUrl(), environment = properties.sourceEnvironment();
            List<JsonNode> incoming = client.fetchAll();
            BluecomProductClient.validateRecords(incoming);
            if (incoming.isEmpty()) throw new ApiException(HttpStatus.BAD_GATEWAY, "PRODUCT_CATALOG_EMPTY",
                    "Danh mục đối tác đang trống; giữ nguyên bản đồng bộ trước để kiểm tra.");
            if (source == null || !Objects.equals(source, properties.sourceBaseUrl())
                    || !Objects.equals(environment, properties.sourceEnvironment()))
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PRODUCT_SOURCE_CHANGED", "Nguồn sản phẩm đã thay đổi trong lúc đồng bộ.");
            Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            String snapshotId = UUID.randomUUID().toString();
            transaction.executeWithoutResult(status -> {
                Map<String, Product> existing = new HashMap<>();
                repository.findAll().forEach(product -> existing.put(product.getSku(), product));
                List<Product> products = new ArrayList<>();
                for (JsonNode item : incoming) {
                    JsonNode extra = item.path("extraData");
                    Set<String> countries = ProductPublicProjection.countries(extra);
                    StringBuilder search = new StringBuilder(item.path("sku").textValue()).append(' ')
                            .append(item.path("name").textValue()).append(' ').append(item.path("regional").asText(""));
                    for (String code : countries) {
                        Locale country = new Locale.Builder().setRegion(code).build();
                        for (Locale language : List.of(Locale.ENGLISH, Locale.forLanguageTag("vi"), Locale.SIMPLIFIED_CHINESE))
                            search.append(' ').append(country.getDisplayCountry(language));
                    }
                    String sku = item.path("sku").textValue();
                    Product product = existing.getOrDefault(sku, new Product(sku));
                    product.update(item.path("name").textValue(), item.path("regional").asText(null),
                            decimal(item,"price"), decimal(item,"sellingPrice"), ProductPublicProjection.currency(item.path("currencyCode")),
                            validityDays(extra), extra.path("volume").isValueNode() ? extra.path("volume").asText(null) : null,
                            item.toString(), normalize(search.toString()), "|" + String.join("|", countries) + "|", now);
                    product.setProvenance(source, environment, snapshotId);
                    products.add(product);
                }
                repository.saveAllAndFlush(products);
                repository.deactivateOtherSnapshots(snapshotId);
            });
        } finally { syncLock.unlock(); }
        return summary();
    }

    public boolean publicAdviceEnabled() { return properties.isPublicAdviceEnabled(); }
    public boolean integrationConfigured() { return properties.configured(); }

    /** One bounded database snapshot supplies the immutable facts, renderer and evidence for a turn. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdviceSnapshot adviceSnapshot(Set<String> countries, Integer days, Budget budget, String exactSku) {
        if (!properties.adviceConfigured()) return new AdviceSnapshot(Availability.UNAVAILABLE, List.of());
        if (budget != null && !properties.isSellingPriceCurrencyConfirmed())
            return new AdviceSnapshot(Availability.UNAVAILABLE, List.of());
        Product latest = currentSourceLatest();
        if (latest == null || latest.getSnapshotId() == null) return new AdviceSnapshot(Availability.UNAVAILABLE, List.of());
        if (!fresh(latest)) return new AdviceSnapshot(Availability.STALE, List.of());
        Specification<Product> spec = currentSourceSpec();
        spec = spec.and((root, query, cb) -> {
            var predicates = new ArrayList<jakarta.persistence.criteria.Predicate>();
            predicates.add(cb.equal(root.get("snapshotId"), latest.getSnapshotId()));
            for (String country : countries) predicates.add(cb.like(root.get("locationCodes"), "%|" + country + "|%"));
            if (days != null) predicates.add(cb.greaterThanOrEqualTo(root.get("durationDays"), days));
            if (budget != null) {
                predicates.add(cb.equal(root.get("currencyCode"), budget.currencyCode()));
                predicates.add(cb.lessThanOrEqualTo(root.get("sellingPrice"), budget.amount()));
            }
            if (exactSku != null) predicates.add(cb.equal(cb.upper(root.get("sku")), exactSku.toUpperCase(Locale.ROOT)));
            return cb.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        });
        Page<Product> found = repository.findAll(spec, PageRequest.of(0, 6, Sort.by("currencyCode", "sellingPrice", "sku")));
        return new AdviceSnapshot(Availability.READY, found.stream().map(this::facts).toList());
    }

    public PageResult search(String query, String country, Integer days, BigDecimal budget, int page, int size, boolean admin) {
        int limit = Math.max(1, Math.min(100, size));
        if (budget != null && !properties.isSellingPriceCurrencyConfirmed())
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"PRODUCT_CURRENCY_UNVERIFIED","Chưa xác minh tiền tệ áp dụng cho giá bán lẻ của đối tác.");
        if (!admin && !properties.adviceConfigured()) return new PageResult(List.of(),0,Math.max(0,page),limit,0);
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
        if (!admin) spec = spec.and(currentSourceSpec());
        Page<Product> found = repository.findAll(spec, PageRequest.of(Math.max(0,page),limit,Sort.by("currencyCode","sellingPrice","sku")));
        return new PageResult(found.stream().map(product -> view(product,admin)).toList(),found.getTotalElements(),found.getNumber(),limit,found.getTotalPages());
    }
    public JsonNode detail(String sku, boolean admin) {
        Product product = repository.findById(sku).filter(Product::isAvailable).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND,"PRODUCT_NOT_FOUND","Không tìm thấy sản phẩm trong danh mục hiện tại."));
        if (!admin && (!properties.adviceConfigured() || !sameSource(product) || !fresh(product)))
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"PRODUCT_CATALOG_UNAVAILABLE","Chưa thể xác minh dữ liệu sản phẩm hiện tại.");
        return view(product,admin);
    }
    private JsonNode view(Product product, boolean admin) {
        ObjectNode raw = payload(product);
        ObjectNode data = admin ? raw : ProductPublicProjection.project(mapper,raw);
        if (!admin && !properties.isSellingPriceCurrencyConfirmed()) data.remove("currencyCode");
        data.put("syncedAt",product.getSyncedAt().toString());
        data.put("available",product.isAvailable());
        data.put("sourceBaseUrl",product.getSourceBaseUrl());
        data.put("sourceEnvironment",product.getSourceEnvironment());
        data.put("snapshotId",product.getSnapshotId());
        return data;
    }
    private ObjectNode payload(Product product) {
        try { return (ObjectNode)mapper.readTree(product.getPayload()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException | ClassCastException invalid) {
            throw new IllegalStateException("Invalid stored product",invalid);
        }
    }
    private ProductFacts facts(Product product) {
        JsonNode extra = ProductPublicProjection.project(mapper,payload(product)).path("extraData");
        ProductFacts.DataAllowance data = null;
        // Bluecom package documentation: volume is a numeric MB string or "unlimited"; dataType 1=TOTAL, 2=DAILY.
        ProductFacts.DataAllowance.Period period = extra.path("dataType").isIntegralNumber() && extra.path("dataType").canConvertToInt() ? switch (extra.path("dataType").intValue()) {
            case 1 -> ProductFacts.DataAllowance.Period.TOTAL;
            case 2 -> ProductFacts.DataAllowance.Period.DAILY;
            default -> ProductFacts.DataAllowance.Period.UNKNOWN;
        } : ProductFacts.DataAllowance.Period.UNKNOWN;
        String volume = extra.path("volume").asText("");
        if ("unlimited".equalsIgnoreCase(volume))
            data = new ProductFacts.DataAllowance(null,"MB",period,true);
        else if (volume.matches("\\d+(?:\\.\\d{1,6})?")) try {
            BigDecimal amount = new BigDecimal(volume);
            if (amount.signum() >= 0 && amount.precision() <= 30)
                data = new ProductFacts.DataAllowance(amount,"MB",period,false);
        } catch (NumberFormatException unknown) { /* Missing/malformed data is not unlimited or zero. */ }
        Set<String> networks = new TreeSet<>();
        String speed = extra.path("speed").asText("");
        if (speed.matches("[2-5]G(?:/[2-5]G)*")) networks.addAll(Arrays.asList(speed.split("/")));
        Boolean topUp = extra.path("supportTopUpType").isIntegralNumber() && extra.path("supportTopUpType").canConvertToInt()
                && extra.path("supportTopUpType").intValue()==1 ? false : null;
        return new ProductFacts(product.getSku(),product.getName(),ProductPublicProjection.countries(extra),
                product.getDurationDays(),data,product.getSellingPrice(),properties.isSellingPriceCurrencyConfirmed() ? product.getCurrencyCode() : null,
                new ProductFacts.Provenance(product.getSourceBaseUrl(),product.getSourceEnvironment(),product.getSnapshotId(),product.getSyncedAt()),
                new ProductFacts.Conditions(networks,topUp));
    }
    private Product currentSourceLatest() {
        if (properties.sourceBaseUrl() == null) return null;
        return repository.findFirstByAvailableTrueAndSourceBaseUrlAndSourceEnvironmentOrderBySyncedAtDesc(
                properties.sourceBaseUrl(),properties.sourceEnvironment());
    }
    private Specification<Product> currentSourceSpec() {
        Instant now = Instant.now();
        return (root,query,cb) -> cb.and(cb.isTrue(root.get("available")),
                cb.equal(root.get("sourceBaseUrl"),properties.sourceBaseUrl()),
                cb.equal(root.get("sourceEnvironment"),properties.sourceEnvironment()),
                cb.isNotNull(root.get("snapshotId")),cb.greaterThanOrEqualTo(root.get("syncedAt"),now.minus(properties.getMaxAge())),
                cb.lessThanOrEqualTo(root.get("syncedAt"),now.plusSeconds(60)));
    }
    private boolean sameSource(Product product) {
        return product.getSnapshotId() != null && Objects.equals(product.getSourceBaseUrl(),properties.sourceBaseUrl())
                && Objects.equals(product.getSourceEnvironment(),properties.sourceEnvironment());
    }
    private boolean fresh(Product product) {
        Instant now = Instant.now();
        return product.getSyncedAt() != null && !product.getSyncedAt().isBefore(now.minus(properties.getMaxAge()))
                && !product.getSyncedAt().isAfter(now.plusSeconds(60));
    }
    private static Integer validityDays(JsonNode extra) {
        JsonNode value = extra.path("duration");
        String unit = extra.path("durationUnit").asText("DAY");
        return value.isIntegralNumber() && value.canConvertToInt() && value.intValue()>0 && value.intValue()<=3650
                && Set.of("DAY","DAYS").contains(unit.toUpperCase(Locale.ROOT)) ? value.intValue() : null;
    }
    private BigDecimal decimal(JsonNode item,String field) { return item.path(field).isNumber() ? item.path(field).decimalValue() : null; }
    public static String normalize(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replace('đ','d').replace('Đ','D').toLowerCase(Locale.ROOT);
    }
}
