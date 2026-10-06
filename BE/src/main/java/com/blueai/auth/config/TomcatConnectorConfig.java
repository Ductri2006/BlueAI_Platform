package com.blueai.auth.config;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Uses Tomcat's asynchronous I/O connector.
 *
 * <p>The NIO connector requires {@code Selector.open()}, which can fail on some Windows
 * installations when the JDK cannot establish its internal loopback pipe. NIO2 uses the
 * Windows asynchronous channel implementation and keeps the HTTP server available in that
 * environment.</p>
 */
@Configuration
public class TomcatConnectorConfig {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> tomcatNio2Connector() {
        return factory -> factory.setProtocol("org.apache.coyote.http11.Http11Nio2Protocol");
    }
}
