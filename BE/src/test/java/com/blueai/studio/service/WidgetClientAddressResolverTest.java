package com.blueai.studio.service;

import com.blueai.studio.config.WidgetProtectionProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WidgetClientAddressResolverTest {

    @Test
    void acceptsForwardedAddressOnlyFromConfiguredTrustedProxy() {
        WidgetProtectionProperties properties = new WidgetProtectionProperties();
        WidgetClientAddressResolver resolver = new WidgetClientAddressResolver(properties);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-For", "203.0.113.8");

        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.10");

        properties.setTrustForwardedHeaders(true);
        assertThat(resolver.resolve(request)).isEqualTo("10.0.0.10");

        properties.setTrustedProxyCidrs(List.of("10.0.0.0/8"));
        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.8");

        request.setRemoteAddr("192.0.2.10");
        assertThat(resolver.resolve(request)).isEqualTo("192.0.2.10");
    }
}
