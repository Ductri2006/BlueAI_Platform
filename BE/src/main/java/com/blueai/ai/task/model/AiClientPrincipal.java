package com.blueai.ai.task.model;

/** Authenticated machine client and the administrator that owns it. */
public record AiClientPrincipal(String clientId, String ownerId) {}
