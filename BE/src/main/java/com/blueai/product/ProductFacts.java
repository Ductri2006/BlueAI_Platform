package com.blueai.product;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

/** Customer-safe facts from one persisted source snapshot; contains no mutable JSON or supplier payload. */
public record ProductFacts(String sku, String name, Set<String> countryCodes, Integer validityDays,
                           DataAllowance data, BigDecimal sellingPrice, String currencyCode,
                           Provenance provenance, Conditions conditions) {
    public ProductFacts {
        countryCodes = Collections.unmodifiableSet(new TreeSet<>(countryCodes));
    }
    public ProductFacts(String sku, String name, Set<String> countryCodes, Integer validityDays,
                        DataAllowance data, BigDecimal sellingPrice, String currencyCode, Provenance provenance) {
        this(sku,name,countryCodes,validityDays,data,sellingPrice,currencyCode,provenance,null);
    }

    public record DataAllowance(BigDecimal amount, String unit, Period period, boolean unlimited) {
        public DataAllowance(BigDecimal amount, String unit, Period period) { this(amount,unit,period,false); }
        public enum Period { TOTAL, DAILY, UNKNOWN }
    }
    public record Conditions(Set<String> supportedNetworks, Boolean topUpSupported) {
        public Conditions { supportedNetworks = Collections.unmodifiableSet(new TreeSet<>(supportedNetworks)); }
    }

    public record Provenance(String sourceBaseUrl, String environment, String snapshotId, Instant syncedAt) { }
}
