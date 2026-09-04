package com.blueai.rag.chat.service;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RagRestClientFactory {

    public RestClient.Builder create() {
        return RestClient.builder();
    }
}
