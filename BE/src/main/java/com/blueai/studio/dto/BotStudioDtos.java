package com.blueai.studio.dto;

import com.blueai.studio.model.BotStatus;
import com.blueai.studio.model.BotCreationTemplate;
import com.blueai.studio.model.DialogNextStep;
import com.blueai.studio.model.DialogNodeType;
import com.blueai.studio.model.DialogContextReusePolicy;
import com.blueai.rag.chat.dto.RagSourceResponse;
import com.blueai.rag.chat.dto.ConversationContextSourceResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;

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
            Boolean includeStarterTemplate,
            BotCreationTemplate template
    ) {
        public CreateBotRequest(String name, String description, String locale, String timezone,
                                Boolean includeStarterTemplate) {
            this(name, description, locale, timezone, includeStarterTemplate, null);
        }
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
            CustomizationInput customization,
            boolean dialogEnabled,
            List<String> widgetDocumentIds
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
            int priority,
            DialogContextReusePolicy contextReusePolicy
    ) {
        public DialogNodeInput(String name, DialogNodeType type, String parentNode, String matchIntent,
                               String matchEntityName, String matchEntityValue, String requiredEntityName,
                               String responseText, String missingEntityPrompt, DialogNextStep nextStep,
                               String jumpToNode, String irrelevantResponseText, int priority) {
            this(name, type, parentNode, matchIntent, matchEntityName, matchEntityValue, requiredEntityName,
                    responseText, missingEntityPrompt, nextStep, jumpToNode, irrelevantResponseText, priority,
                    DialogContextReusePolicy.NONE);
        }
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
            @Valid CustomizationInput customization,
            Boolean dialogEnabled,
            @Size(max = 100) List<@NotBlank @Pattern(regexp = "[0-9a-fA-F-]{36}") String> widgetDocumentIds
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
            @Size(max = 100) String pendingIntent,
            @Size(max = 100) String pendingEntity,
            @Size(max = 100) String lastIntent,
            @Size(max = 50) Map<@NotBlank @Size(max = 100) String, @NotBlank @Size(max = 150) String> variables,
            @Size(max = 120) String waitingNode,
            @Size(max = 1) Map<@NotBlank @Size(max = 100) String, @NotBlank @Size(max = 150) String> suggestedVariables,
            @Size(max = 500) String suggestionSource
    ) {
        public PreviewContext(String pendingIntent, String pendingEntity, String lastIntent,
                              Map<String, String> variables, String waitingNode) {
            this(pendingIntent, pendingEntity, lastIntent, variables, waitingNode, Map.of(), null);
        }

        public PreviewContext(String pendingIntent, String pendingEntity, String lastIntent, Map<String, String> variables) {
            this(pendingIntent, pendingEntity, lastIntent, variables, null);
        }

        public PreviewContext {
            variables = variables == null ? Map.of() : Map.copyOf(variables);
            suggestedVariables = suggestedVariables == null ? Map.of() : Map.copyOf(suggestedVariables);
        }
    }

    public record PreviewHistoryMessage(
            @NotBlank @Pattern(regexp = "user|assistant") String role,
            @NotBlank @Size(max = 4000) String content
    ) { }

    public record PreviewRequest(
            @NotBlank @Size(max = 2000) String message,
            @Valid PreviewContext context,
            @Size(max = 36) String conversationId,
            @Size(max = 20) List<@Valid PreviewHistoryMessage> history
    ) {
        public PreviewRequest(String message, PreviewContext context) {
            this(message, context, null, List.of());
        }
        public PreviewRequest {
            history = history == null ? List.of() : List.copyOf(history);
        }
    }

    public record DialogTrace(String route, String reason, List<String> visitedNodes,
                              Map<String, String> confirmedEntities, String contextSource) { }

    public record RuntimeResponse(
            String message,
            String matchedIntent,
            Map<String, String> recognizedEntities,
            String matchedNode,
            PreviewContext context,
            boolean fallback,
            boolean dialogHandled,
            DialogTrace trace,
            List<RagSourceResponse> sources,
            List<ConversationContextSourceResponse> contextSources
    ) {
        public RuntimeResponse(String message, String matchedIntent, Map<String, String> recognizedEntities,
                               String matchedNode, PreviewContext context, boolean fallback, boolean dialogHandled) {
            this(message, matchedIntent, recognizedEntities, matchedNode, context, fallback, dialogHandled,
                    null, List.of(), List.of());
        }

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

        public RuntimeResponse withTrace(DialogTrace value) {
            return new RuntimeResponse(message, matchedIntent, recognizedEntities, matchedNode, context,
                    fallback, dialogHandled, value, sources, contextSources);
        }

        public RuntimeResponse withEvidence(List<RagSourceResponse> documents,
                                             List<ConversationContextSourceResponse> contexts) {
            return new RuntimeResponse(message, matchedIntent, recognizedEntities, matchedNode, context,
                    fallback, dialogHandled, trace, documents, contexts);
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
        public PublicRuntimeOutput {
            // Also applies when an older cached turn is deserialized for a retry.
            message = com.blueai.rag.chat.service.AnswerPresentation.text(message);
        }

        public static PublicRuntimeOutput from(RuntimeResponse response) {
            return new PublicRuntimeOutput(com.blueai.rag.chat.service.AnswerPresentation.text(
                    response.message(), response.sources()), response.fallback());
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
