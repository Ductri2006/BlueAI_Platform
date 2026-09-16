package com.blueai.studio.dto;

import com.blueai.studio.model.BotStatus;
import com.blueai.studio.model.DialogNextStep;
import com.blueai.studio.model.DialogNodeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class BotStudioDtos {

    private BotStudioDtos() {
    }

    public record CreateBotRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @Size(max = 20) String locale,
            @Size(max = 60) String timezone,
            Boolean includeStarterTemplate
    ) {
    }

    public record BotSummaryResponse(
            String id,
            String name,
            String description,
            String locale,
            String timezone,
            boolean blueAiCoreEnabled,
            String customInstructions,
            BotStatus status,
            String publicKey,
            long draftRevision,
            String publishedReleaseId,
            Instant updatedAt,
            boolean active,
            CustomizationInput customization
    ) {
    }

    /**
     * Compact, persisted BlueAI behavior settings. Every field is optional on
     * input so releases created by older versions remain readable; the service
     * fills defaults before writing a release snapshot.
     */
    public record CustomizationInput(
            @Size(max = 120) String identityRole,
            @Size(max = 40) String tonePreset,
            @Size(max = 24) String answerLength,
            @Size(max = 40) String responseStructure,
            @Size(max = 24) String proactivity,
            @Size(max = 40) String citationPolicy,
            @Size(max = 80) String noSourcePolicy,
            @Size(max = 1000) String preferredTerms,
            @Size(max = 1000) String forbiddenTerms,
            @Size(max = 1000) String fallbackMessage,
            @Size(max = 1000) String escalationMessage,
            @Size(max = 120) String responseProfile
    ) {
        public static CustomizationInput defaults() {
            return new CustomizationInput(
                    "Trợ lý BlueAI",
                    "professional",
                    "balanced",
                    "direct",
                    "ask_when_needed",
                    "always_when_available",
                    "say_not_found",
                    null,
                    null,
                    "Mình chưa tìm thấy đủ thông tin trong kho tri thức để trả lời chắc chắn.",
                    "Mình sẽ chuyển yêu cầu này tới đội hỗ trợ để được xử lý tiếp.",
                    null
            );
        }

        public CustomizationInput withDefaults() {
            CustomizationInput defaults = defaults();
            return new CustomizationInput(
                    valueOrDefault(identityRole, defaults.identityRole()),
                    valueOrDefault(tonePreset, defaults.tonePreset()),
                    valueOrDefault(answerLength, defaults.answerLength()),
                    valueOrDefault(responseStructure, defaults.responseStructure()),
                    valueOrDefault(proactivity, defaults.proactivity()),
                    valueOrDefault(citationPolicy, defaults.citationPolicy()),
                    valueOrDefault(noSourcePolicy, defaults.noSourcePolicy()),
                    trimToNull(preferredTerms),
                    trimToNull(forbiddenTerms),
                    valueOrDefault(fallbackMessage, defaults.fallbackMessage()),
                    valueOrDefault(escalationMessage, defaults.escalationMessage()),
                    trimToNull(responseProfile)
            );
        }

        private static String valueOrDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        private static String trimToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    public record IntentInput(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotEmpty List<@NotBlank @Size(max = 500) String> examples
    ) {
    }

    public record EntityValueInput(
            @NotBlank @Size(max = 150) String value,
            List<@NotBlank @Size(max = 150) String> synonyms
    ) {
    }

    public record EntityInput(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotEmpty List<@Valid EntityValueInput> values
    ) {
    }

    public record DialogNodeInput(
            @NotBlank @Size(max = 120) String name,
            @NotNull DialogNodeType type,
            @Size(max = 120) String parentNode,
            @Size(max = 100) String matchIntent,
            @Size(max = 100) String matchEntityName,
            @Size(max = 150) String matchEntityValue,
            @Size(max = 100) String requiredEntityName,
            @Size(max = 4000) String responseText,
            @Size(max = 1000) String missingEntityPrompt,
            DialogNextStep nextStep,
            @Size(max = 120) String jumpToNode,
            @Size(max = 1000) String irrelevantResponseText,
            int priority
    ) {
    }

    public record SaveConfigurationRequest(
            long expectedRevision,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 500) String description,
            @NotBlank @Size(max = 20) String locale,
            @NotBlank @Size(max = 60) String timezone,
            Boolean blueAiCoreEnabled,
            @Size(max = 2000) String customInstructions,
            @NotNull List<@Valid IntentInput> intents,
            @NotNull List<@Valid EntityInput> entities,
            @NotNull List<@Valid DialogNodeInput> dialogNodes,
            @Valid CustomizationInput customization
    ) {
    }

    public record BotConfigurationResponse(
            BotSummaryResponse bot,
            List<IntentInput> intents,
            List<EntityInput> entities,
            List<DialogNodeInput> dialogNodes,
            CustomizationInput customization
    ) {
    }

    public record PreviewContext(
            String pendingIntent,
            String pendingEntity,
            String lastIntent,
            Map<String, String> variables,
            String waitingNode
    ) {
        public PreviewContext(String pendingIntent, String pendingEntity, String lastIntent, Map<String, String> variables) {
            this(pendingIntent, pendingEntity, lastIntent, variables, null);
        }

        public PreviewContext {
            variables = variables == null ? Map.of() : Map.copyOf(variables);
        }
    }

    public record PreviewRequest(
            @NotBlank @Size(max = 2000) String message,
            PreviewContext context
    ) {
    }

    public record RuntimeResponse(
            String message,
            String matchedIntent,
            Map<String, String> recognizedEntities,
            String matchedNode,
            PreviewContext context,
            boolean fallback,
            boolean dialogHandled
    ) {
        public RuntimeResponse(
                String message,
                String matchedIntent,
                Map<String, String> recognizedEntities,
                String matchedNode,
                PreviewContext context,
                boolean fallback
        ) {
            this(message, matchedIntent, recognizedEntities, matchedNode, context, fallback, false);
        }
    }

    public record PublishResponse(
            String releaseId,
            long revision,
            String publicKey,
            Instant publishedAt
    ) {
    }

    public record CreateWidgetSessionResponse(
            String sessionId,
            String accessToken,
            String releaseId,
            long revision,
            PublicRuntimeOutput welcome,
            Instant expiresAt
    ) {
    }

    public record PublicRuntimeOutput(
            String message,
            boolean fallback
    ) {
        public static PublicRuntimeOutput from(RuntimeResponse response) {
            return new PublicRuntimeOutput(response.message(), response.fallback());
        }
    }

    public record RuntimeMessageRequest(
            @NotBlank @Size(max = 80) String requestId,
            @NotBlank @Size(max = 2000) String message
    ) {
    }

    public record RuntimeMessageResponse(
            String requestId,
            String sessionId,
            String releaseId,
            long revision,
            PublicRuntimeOutput result
    ) {
    }
}
