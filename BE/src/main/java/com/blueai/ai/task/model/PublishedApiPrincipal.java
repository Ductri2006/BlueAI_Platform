package com.blueai.ai.task.model;

/** A published bot API key can enter only the simple chat endpoint. */
public record PublishedApiPrincipal(String keyId, String botId, String ownerId) {}
