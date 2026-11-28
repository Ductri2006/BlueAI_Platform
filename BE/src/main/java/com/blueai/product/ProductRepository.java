package com.blueai.product;

import org.springframework.data.jpa.repository.*;
import java.time.Instant;

public interface ProductRepository extends JpaRepository<Product, String>, JpaSpecificationExecutor<Product> {
    long countByAvailableTrue();
    Product findFirstByOrderBySyncedAtDesc();
    Product findFirstByAvailableTrueAndSourceBaseUrlAndSourceEnvironmentOrderBySyncedAtDesc(
            String sourceBaseUrl, String sourceEnvironment);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Product p set p.available = false where p.snapshotId is null or p.snapshotId <> :snapshotId")
    int deactivateOtherSnapshots(String snapshotId);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Product p set p.available = false where p.syncedAt <> :syncedAt")
    int deactivateOlder(Instant syncedAt);
}
