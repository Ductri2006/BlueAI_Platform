package com.blueai.rag.chat.service;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
public class RagRestClientFactory {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    public RestClient.Builder create() {
        // Keep RAG traffic on HttpURLConnection. Spring may otherwise auto-select
        // JdkClientHttpRequestFactory, whose Java 26/Windows selector can fail while
        // opening a loopback connection before the request reaches the upstream service.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory);
    }
}
