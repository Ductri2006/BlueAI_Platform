package com.blueai.rag.conversation.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Stores only server-produced evidence, never a client-supplied trust flag. */
@Converter
public class MessageEvidenceConverter implements AttributeConverter<MessageEvidence, String> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(MessageEvidence evidence) {
        if (evidence == null) return null;
        try {
            return MAPPER.writeValueAsString(evidence);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize message evidence.");
        }
    }

    @Override
    public MessageEvidence convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, MessageEvidence.class);
        } catch (JsonProcessingException exception) {
            // Do not leak stored excerpts through exception messages.
            throw new IllegalStateException("Stored message evidence is invalid.");
        }
    }
}
