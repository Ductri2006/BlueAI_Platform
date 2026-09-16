package com.blueai.studio.service;

import com.blueai.studio.dto.BotStudioDtos.DialogNodeInput;
import com.blueai.studio.dto.BotStudioDtos.EntityInput;
import com.blueai.studio.dto.BotStudioDtos.IntentInput;
import com.blueai.studio.dto.BotStudioDtos.CustomizationInput;

import java.util.List;

public record BotSnapshot(
        int schemaVersion,
        String botId,
        String ownerId,
        String name,
        String description,
        long revision,
        String locale,
        String timezone,
        Boolean blueAiCoreEnabled,
        String customInstructions,
        CustomizationInput customization,
        List<IntentInput> intents,
        List<EntityInput> entities,
        List<DialogNodeInput> dialogNodes
) {
}
