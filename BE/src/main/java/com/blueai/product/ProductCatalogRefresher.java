package com.blueai.product;

import com.blueai.auth.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="app.products.auto-sync-enabled", havingValue="true")
public class ProductCatalogRefresher {
    private static final Logger log = LoggerFactory.getLogger(ProductCatalogRefresher.class);
    private final ProductCatalogService catalog;
    private final ProductProperties properties;

    public ProductCatalogRefresher(ProductCatalogService catalog, ProductProperties properties) {
        this.catalog=catalog;this.properties=properties;
    }

    @Async
    @Scheduled(initialDelayString="${app.products.initial-delay:10s}", fixedDelayString="${app.products.refresh-interval:15m}")
    public void refresh() {
        if (!properties.configured()) return;
        try {
            var summary = catalog.sync();
            log.info("Product catalogue refreshed: {} available products",summary.available());
        } catch (ApiException ex) {
            // Never log upstream bodies, credentials, or exception chains.
            log.warn("Product catalogue refresh skipped/failed: {}",ex.getCode());
        } catch (RuntimeException ex) {
            log.warn("Product catalogue refresh failed; retaining the previous catalogue");
        }
    }
}
