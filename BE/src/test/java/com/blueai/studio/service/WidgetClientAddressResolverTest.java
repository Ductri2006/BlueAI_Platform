package com.blueai.studio.service;

import com.blueai.studio.config.WidgetProtectionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class WidgetClientAddressResolverTest {

    @Test
    void ignoresForwardedAddressUnlessDeploymentExplicitlyTrustsTheProxy() {
        WidgetProtectionProperties properties = new WidgetProtectionProperties();
        WidgetClientAddressResolver resolver = new WidgetClientAddressResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-For", "203.0.113.8");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.10");

        properties.setTrustForwardedHeaders(true);
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.8");
    }
}
