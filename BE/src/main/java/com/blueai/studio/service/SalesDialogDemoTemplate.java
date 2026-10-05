package com.blueai.studio.service;

import com.blueai.studio.dto.BotStudioDtos.DialogNodeInput;
import com.blueai.studio.dto.BotStudioDtos.EntityInput;
import com.blueai.studio.dto.BotStudioDtos.IntentInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

/** A local, versioned fixture: no provider calls, prices, orders, or automatic activation. */
final class SalesDialogDemoTemplate {
    private SalesDialogDemoTemplate() {
    }

    static Configuration load(ObjectMapper objectMapper) {
        try (var input = new ClassPathResource("studio/sales-dialog-demo.json").getInputStream()) {
            return objectMapper.readValue(input, Configuration.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load the bundled sales Dialog demo", exception);
        }
    }

    record Configuration(List<IntentInput> intents, List<EntityInput> entities, List<DialogNodeInput> dialogNodes) {
        Configuration {
            intents = List.copyOf(intents);
            entities = List.copyOf(entities);
            dialogNodes = List.copyOf(dialogNodes);
        }
    }
}
