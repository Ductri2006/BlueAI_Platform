package com.blueai.product;

import org.springframework.data.jpa.repository.*;
import java.time.Instant;

public interface ProductRepository extends JpaRepository<Product, String>, JpaSpecificationExecutor<Product> {
    long countByAvailableTrue();
    Product findFirstByOrderBySyncedAtDesc();
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Product p set p.available = false where p.syncedAt <> :syncedAt")
    int deactivateOlder(Instant syncedAt);
}
