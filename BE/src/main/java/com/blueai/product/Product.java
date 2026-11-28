package com.blueai.product;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "products")
public class Product {
    @Id @Column(length = 160) private String sku;
    @Column(nullable = false, length = 500) private String name;
    @Column(length = 500) private String region;
    @Column(precision = 19, scale = 4) private BigDecimal costPrice;
    @Column(precision = 19, scale = 4) private BigDecimal sellingPrice;
    @Column(length = 10) private String currencyCode;
    private Integer durationDays;
    @Column(length = 100) private String volumeMb;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT") private String payload;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT") private String searchText;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT") private String locationCodes;
    @Column(nullable = false) private boolean available;
    @Column(nullable = false) private Instant syncedAt;
    @Column(length = 512) private String sourceBaseUrl;
    @Column(length = 20) private String sourceEnvironment;
    @Column(length = 36) private String snapshotId;
    protected Product() { }
    public Product(String sku) { this.sku = sku; }
    public void update(String name, String region, BigDecimal cost, BigDecimal selling, String currency,
                       Integer days, String volume, String payload, String searchText, String codes, Instant at) {
        this.name = name; this.region = region; costPrice = cost; sellingPrice = selling; currencyCode = currency;
        durationDays = days; volumeMb = volume; this.payload = payload; this.searchText = searchText;
        locationCodes = codes; available = true; syncedAt = at;
    }
    public String getSku() { return sku; }
    public String getName() { return name; }
    public String getRegion() { return region; }
    public BigDecimal getCostPrice() { return costPrice; }
    public BigDecimal getSellingPrice() { return sellingPrice; }
    public String getCurrencyCode() { return currencyCode; }
    public Integer getDurationDays() { return durationDays; }
    public String getVolumeMb() { return volumeMb; }
    public String getPayload() { return payload; }
    public boolean isAvailable() { return available; }
    public Instant getSyncedAt() { return syncedAt; }
    public String getSourceBaseUrl() { return sourceBaseUrl; }
    public String getSourceEnvironment() { return sourceEnvironment; }
    public String getSnapshotId() { return snapshotId; }
    public void setProvenance(String baseUrl, String environment, String snapshotId) {
        this.sourceBaseUrl = baseUrl;
        this.sourceEnvironment = environment;
        this.snapshotId = snapshotId;
    }
}
