package com.blueai.product;

import com.blueai.auth.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductCatalogRefresherTest {
    private final ProductCatalogService catalog = mock(ProductCatalogService.class);
    private final ProductProperties properties = new ProductProperties();
    private final ProductCatalogRefresher refresher = new ProductCatalogRefresher(catalog,properties);

    @Test void doesNotCallPartnerWithoutConfiguration() {
        refresher.refresh();
        verifyNoInteractions(catalog);
    }
    @Test void refreshesConfiguredCatalogueAndSurvivesUpstreamFailure() {
        properties.setApiKey("test-key");
        when(catalog.sync()).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY,"PRODUCT_UPSTREAM_IP_FORBIDDEN","private diagnostics"));
        assertThatCode(refresher::refresh).doesNotThrowAnyException();
        verify(catalog).sync();
    }
}
