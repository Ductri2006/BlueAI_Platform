package com.blueai.studio.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.auth.model.User;
import com.blueai.auth.model.UserRole;
import com.blueai.auth.model.UserStatus;
import com.blueai.auth.repository.UserRepository;
import com.blueai.rag.chat.service.RagChatService;
import com.blueai.studio.dto.BotStudioDtos.BotConfigurationResponse;
import com.blueai.studio.dto.BotStudioDtos.BotSummaryResponse;
import com.blueai.studio.dto.BotStudioDtos.CreateBotRequest;
import com.blueai.studio.dto.BotStudioDtos.CustomizationInput;
import com.blueai.studio.dto.BotStudioDtos.DialogNodeInput;
import com.blueai.studio.dto.BotStudioDtos.EntityInput;
import com.blueai.studio.dto.BotStudioDtos.EntityValueInput;
import com.blueai.studio.dto.BotStudioDtos.IntentInput;
import com.blueai.studio.dto.BotStudioDtos.PreviewRequest;
import com.blueai.studio.dto.BotStudioDtos.PublishResponse;
import com.blueai.studio.dto.BotStudioDtos.RuntimeResponse;
import com.blueai.studio.dto.BotStudioDtos.SaveConfigurationRequest;
import com.blueai.studio.model.Bot;
import com.blueai.studio.model.BotDialogNode;
import com.blueai.studio.model.BotEntityDefinition;
import com.blueai.studio.model.BotEntityValue;
import com.blueai.studio.model.BotIntent;
import com.blueai.studio.model.BotRelease;
import com.blueai.studio.model.DialogNextStep;
import com.blueai.studio.model.DialogNodeType;
import com.blueai.studio.repository.BotDialogNodeRepository;
import com.blueai.studio.repository.BotEntityDefinitionRepository;
import com.blueai.studio.repository.BotEntityValueRepository;
import com.blueai.studio.repository.BotIntentRepository;
import com.blueai.studio.repository.BotReleaseRepository;
import com.blueai.studio.repository.BotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class BotStudioService {

    private final UserRepository userRepository;
    private final BotRepository botRepository;
    private final BotIntentRepository intentRepository;
    private final BotEntityDefinitionRepository entityRepository;
    private final BotEntityValueRepository entityValueRepository;
    private final BotDialogNodeRepository dialogNodeRepository;
    private final BotReleaseRepository releaseRepository;
    private final DialogRuntimeService runtimeService;
    private final RagChatService ragChatService;
    private final ObjectMapper objectMapper;

    public BotStudioService(
            UserRepository userRepository,
            BotRepository botRepository,
            BotIntentRepository intentRepository,
            BotEntityDefinitionRepository entityRepository,
            BotEntityValueRepository entityValueRepository,
            BotDialogNodeRepository dialogNodeRepository,
            BotReleaseRepository releaseRepository,
            DialogRuntimeService runtimeService,
            RagChatService ragChatService,
            ObjectMapper objectMapper
    ) {
        this.userRepository = userRepository;
        this.botRepository = botRepository;
        this.intentRepository = intentRepository;
        this.entityRepository = entityRepository;
        this.entityValueRepository = entityValueRepository;
        this.dialogNodeRepository = dialogNodeRepository;
        this.releaseRepository = releaseRepository;
        this.runtimeService = runtimeService;
        this.ragChatService = ragChatService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<BotSummaryResponse> list(String ownerId) {
        return botRepository.findByOwner_IdOrderByUpdatedAtDesc(ownerId).stream().map(this::summary).toList();
    }

    @Transactional
    public BotConfigurationResponse create(String ownerId, CreateBotRequest request) {
        User owner = requireActiveUser(ownerId);
        String locale = blankToDefault(request.locale(), "vi-VN");
        String timezone = blankToDefault(request.timezone(), "Asia/Ho_Chi_Minh");
        Bot bot = botRepository.saveAndFlush(new Bot(
                owner,
                request.name().trim(),
                trimToNull(request.description()),
                locale,
                timezone
        ));
        if (!Boolean.FALSE.equals(request.includeStarterTemplate())) {
            persistConfiguration(bot, starterIntents(), starterEntities(), starterNodes());
        }
        return configuration(bot);
    }

    @Transactional(readOnly = true)
    public BotConfigurationResponse get(String ownerId, String botId) {
        return configuration(requireOwnedBot(ownerId, botId));
    }

    @Transactional
    public BotConfigurationResponse save(String ownerId, String botId, SaveConfigurationRequest request) {
        Bot bot = requireOwnedBot(ownerId, botId);
        if (bot.getDraftRevision() != request.expectedRevision()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "REVISION_CONFLICT",
                    "Bản nháp đã được thay đổi ở nơi khác. Hãy tải lại trước khi lưu."
            );
        }
        validate(request.intents(), request.entities(), request.dialogNodes());
        bot.updateMetadata(
                request.name().trim(),
                trimToNull(request.description()),
                request.locale().trim(),
                request.timezone().trim()
        );
        bot.updateCustomization(
                !Boolean.FALSE.equals(request.blueAiCoreEnabled()),
                trimToNull(request.customInstructions()),
                customization(request.customization()).identityRole(),
                customization(request.customization()).tonePreset(),
                customization(request.customization()).answerLength(),
                customization(request.customization()).responseStructure(),
                customization(request.customization()).proactivity(),
                customization(request.customization()).citationPolicy(),
                customization(request.customization()).noSourcePolicy(),
                customization(request.customization()).preferredTerms(),
                customization(request.customization()).forbiddenTerms(),
                customization(request.customization()).fallbackMessage(),
                customization(request.customization()).escalationMessage(),
                customization(request.customization()).responseProfile()
        );
        replaceConfiguration(bot, request.intents(), request.entities(), request.dialogNodes());
        bot.advanceDraftRevision();
        botRepository.saveAndFlush(bot);
        return configuration(bot);
    }

    @Transactional(readOnly = true)
    public RuntimeResponse preview(String ownerId, String botId, PreviewRequest request) {
        Bot bot = requireOwnedBot(ownerId, botId);
        BotSnapshot snapshot = snapshot(bot);
        RuntimeResponse deterministic = runtimeService.evaluate(snapshot, request.context(), request.message().trim());
        if (deterministic.fallback()
                && deterministic.context().pendingEntity() == null
                && !deterministic.dialogHandled()
                && bot.isBlueAiCoreEnabled()) {
            try {
                String answer = ragChatService.answerForCustomAssistant(
                        ownerId,
                        bot.getName(),
                        customization(snapshot.customization()),
                        bot.getCustomInstructions(),
                        request.message().trim()
                );
                if (!isBlank(answer)) {
                    return new RuntimeResponse(
                            answer,
                            deterministic.matchedIntent(),
                            deterministic.recognizedEntities(),
                            "BlueAI Core",
                            deterministic.context(),
                            false
                    );
                }
            } catch (ApiException ignored) {
                // Preview remains usable with deterministic fallback if shared RAG/LLM is unavailable.
            }
        }
        return deterministic;
    }

    @Transactional
    public PublishResponse publish(String ownerId, String botId) {
        Bot bot = requireOwnedBot(ownerId, botId);
        BotSnapshot snapshot = snapshot(bot);
        validate(snapshot.intents(), snapshot.entities(), snapshot.dialogNodes());
        BotRelease release = releaseRepository.findByBot_IdAndRevision(botId, bot.getDraftRevision())
                .orElseGet(() -> releaseRepository.saveAndFlush(new BotRelease(
                        bot,
                        bot.getDraftRevision(),
                        writeJson(snapshot)
                )));
        bot.publish(release.getId());
        botRepository.saveAndFlush(bot);
        return new PublishResponse(release.getId(), release.getRevision(), bot.getPublicKey(), release.getCreatedAt());
    }

    @Transactional
    public BotConfigurationResponse activate(String ownerId, String botId) {
        User owner = requireActiveUser(ownerId);
        if (owner.getRole() != UserRole.ADMIN) {
            throw new ApiException(HttpStatus.FORBIDDEN, "STUDIO_ADMIN_REQUIRED", "Chỉ Admin mới có thể áp dụng cấu hình cho Hỏi BlueAI.");
        }
        Bot bot = requireOwnedBot(ownerId, botId);
        if (bot.getStatus() != com.blueai.studio.model.BotStatus.PUBLISHED
                || bot.getPublishedReleaseId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "BOT_NOT_PUBLISHED", "Chỉ cấu hình đã phát hành mới có thể dùng cho Hỏi BlueAI.");
        }
        activateBot(bot);
        return configuration(bot);
    }

    @Transactional(readOnly = true)
    public BotConfigurationResponse active() {
        return botRepository.findByActiveTrueOrderByUpdatedAtDesc().stream()
                .filter(bot -> bot.getStatus() == com.blueai.studio.model.BotStatus.PUBLISHED
                        && bot.getPublishedReleaseId() != null)
                .findFirst()
                .flatMap(bot -> {
                    String releaseId = bot.getActiveReleaseId() == null
                            ? bot.getPublishedReleaseId()
                            : bot.getActiveReleaseId();
                    return releaseRepository.findByIdAndBot_Id(releaseId, bot.getId());
                })
                .map(this::activeConfiguration)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ACTIVE_BLUEAI_NOT_CONFIGURED", "Chưa có cấu hình BlueAI chính được kích hoạt."));
    }

    BotSnapshot snapshot(Bot bot) {
        BotConfigurationResponse configuration = configuration(bot);
        return new BotSnapshot(
                2,
                bot.getId(),
                bot.getOwner().getId(),
                bot.getName(),
                bot.getDescription(),
                bot.getDraftRevision(),
                bot.getLocale(),
                bot.getTimezone(),
                bot.isBlueAiCoreEnabled(),
                bot.getCustomInstructions(),
                customization(bot),
                configuration.intents(),
                configuration.entities(),
                configuration.dialogNodes()
        );
    }

    BotConfigurationResponse configuration(Bot bot) {
        List<IntentInput> intents = intentRepository.findByBot_IdOrderByNameAsc(bot.getId()).stream()
                .map(intent -> new IntentInput(intent.getName(), intent.getDescription(), intent.getExamples()))
                .toList();

        Map<String, List<EntityValueInput>> valuesByEntityId = new LinkedHashMap<>();
        for (BotEntityValue value : entityValueRepository.findByEntity_Bot_IdOrderByEntity_NameAscCanonicalValueAsc(bot.getId())) {
            valuesByEntityId.computeIfAbsent(value.getEntity().getId(), ignored -> new ArrayList<>())
                    .add(new EntityValueInput(value.getCanonicalValue(), value.getSynonyms()));
        }
        List<EntityInput> entities = entityRepository.findByBot_IdOrderByNameAsc(bot.getId()).stream()
                .map(entity -> new EntityInput(
                        entity.getName(),
                        entity.getDescription(),
                        valuesByEntityId.getOrDefault(entity.getId(), List.of())
                ))
                .toList();

        List<DialogNodeInput> nodes = dialogNodeRepository.findByBot_IdOrderByPriorityAsc(bot.getId()).stream()
                .map(node -> new DialogNodeInput(
                        node.getNodeName(),
                        node.getNodeType(),
                        node.getParentNode(),
                        node.getMatchIntent(),
                        node.getMatchEntityName(),
                        node.getMatchEntityValue(),
                        node.getRequiredEntityName(),
                        node.getResponseText(),
                        node.getMissingEntityPrompt(),
                        node.getNextStep() == null ? DialogNextStep.WAIT_FOR_REPLY : node.getNextStep(),
                        node.getJumpToNode(),
                        node.getIrrelevantResponseText(),
                        node.getPriority()
                ))
                .toList();
        return new BotConfigurationResponse(summary(bot), intents, entities, nodes, customization(bot));
    }

    private BotConfigurationResponse activeConfiguration(BotRelease release) {
        Bot bot = release.getBot();
        BotSnapshot snapshot = readJson(release.getConfigurationSnapshot());
        CustomizationInput activeCustomization = customization(snapshot.customization());
        BotSummaryResponse activeSummary = new BotSummaryResponse(
                bot.getId(),
                valueOrDefault(snapshot.name(), bot.getName()),
                snapshot.description(),
                valueOrDefault(snapshot.locale(), bot.getLocale()),
                valueOrDefault(snapshot.timezone(), bot.getTimezone()),
                snapshot.blueAiCoreEnabled() == null ? bot.isBlueAiCoreEnabled() : snapshot.blueAiCoreEnabled(),
                snapshot.customInstructions(),
                com.blueai.studio.model.BotStatus.PUBLISHED,
                bot.getPublicKey(),
                snapshot.revision(),
                release.getId(),
                release.getCreatedAt(),
                true,
                activeCustomization
        );
        return new BotConfigurationResponse(
                activeSummary,
                snapshot.intents() == null ? List.of() : snapshot.intents(),
                snapshot.entities() == null ? List.of() : snapshot.entities(),
                snapshot.dialogNodes() == null ? List.of() : snapshot.dialogNodes(),
                activeCustomization
        );
    }

    Bot requireOwnedBot(String ownerId, String botId) {
        return botRepository.findByIdAndOwner_Id(botId, ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "BOT_NOT_FOUND", "Không tìm thấy chatbot."));
    }

    private User requireActiveUser(String ownerId) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "User no longer exists."));
        if (owner.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "USER_DISABLED", "User account is disabled.");
        }
        return owner;
    }

    private void replaceConfiguration(Bot bot, List<IntentInput> intents, List<EntityInput> entities, List<DialogNodeInput> nodes) {
        entityValueRepository.deleteByEntity_Bot_Id(bot.getId());
        dialogNodeRepository.deleteByBot_Id(bot.getId());
        intentRepository.deleteByBot_Id(bot.getId());
        entityRepository.deleteByBot_Id(bot.getId());
        entityValueRepository.flush();
        dialogNodeRepository.flush();
        intentRepository.flush();
        entityRepository.flush();
        persistConfiguration(bot, intents, entities, nodes);
    }

    private void persistConfiguration(Bot bot, List<IntentInput> intents, List<EntityInput> entities, List<DialogNodeInput> nodes) {
        for (IntentInput input : intents) {
            intentRepository.save(new BotIntent(
                    bot,
                    input.name().trim(),
                    trimToNull(input.description()),
                    input.examples().stream().map(String::trim).toList()
            ));
        }
        for (EntityInput input : entities) {
            BotEntityDefinition entity = entityRepository.saveAndFlush(new BotEntityDefinition(
                    bot,
                    input.name().trim(),
                    trimToNull(input.description())
            ));
            for (EntityValueInput value : input.values()) {
                entityValueRepository.save(new BotEntityValue(
                        entity,
                        value.value().trim(),
                        value.synonyms() == null ? List.of() : value.synonyms().stream().map(String::trim).toList()
                ));
            }
        }
        for (DialogNodeInput input : nodes) {
            dialogNodeRepository.save(new BotDialogNode(
                    bot,
                    input.name().trim(),
                    input.type(),
                    trimToNull(input.parentNode()),
                    trimToNull(input.matchIntent()),
                    trimToNull(input.matchEntityName()),
                    trimToNull(input.matchEntityValue()),
                    trimToNull(input.requiredEntityName()),
                    blankToDefault(input.responseText(), ""),
                    trimToNull(input.missingEntityPrompt()),
                    input.nextStep() == null ? DialogNextStep.WAIT_FOR_REPLY : input.nextStep(),
                    trimToNull(input.jumpToNode()),
                    trimToNull(input.irrelevantResponseText()),
                    input.priority()
            ));
        }
    }

    private void validate(List<IntentInput> intents, List<EntityInput> entities, List<DialogNodeInput> nodes) {
        Set<String> intentNames = uniqueNames(intents.stream().map(IntentInput::name).toList(), "intent");
        Set<String> entityNames = uniqueNames(entities.stream().map(EntityInput::name).toList(), "entity");

        Map<String, String> trainingExamples = new HashMap<>();
        for (IntentInput intent : intents) {
            if (intent.examples() == null || intent.examples().size() < 2) {
                invalid("Intent #" + intent.name() + " cần ít nhất 2 câu mẫu.");
            }
            for (String example : intent.examples()) {
                String normalized = DialogRuntimeService.normalize(example);
                String existing = trainingExamples.putIfAbsent(normalized, intent.name());
                if (existing != null && !existing.equalsIgnoreCase(intent.name())) {
                    invalid("Câu mẫu ‘" + example + "’ đang thuộc cả #" + existing + " và #" + intent.name() + ".");
                }
            }
        }

        for (EntityInput entity : entities) {
            Map<String, String> aliases = new HashMap<>();
            for (EntityValueInput value : entity.values()) {
                List<String> allAliases = new ArrayList<>();
                allAliases.add(value.value());
                if (value.synonyms() != null) allAliases.addAll(value.synonyms());
                for (String alias : allAliases) {
                    String normalized = DialogRuntimeService.normalize(alias);
                    String previous = aliases.putIfAbsent(normalized, value.value());
                    if (previous != null && !previous.equalsIgnoreCase(value.value())) {
                        invalid("Entity @" + entity.name() + " có alias ‘" + alias + "’ trùng giữa " + previous + " và " + value.value() + ".");
                    }
                }
            }
        }

        long welcomeCount = nodes.stream().filter(node -> node.type() == DialogNodeType.WELCOME).count();
        long fallbackCount = nodes.stream().filter(node -> node.type() == DialogNodeType.FALLBACK).count();
        Set<String> nodeNames = uniqueNames(nodes.stream().map(DialogNodeInput::name).toList(), "dialog node");
        if (welcomeCount != 1) invalid("Cấu hình phải có đúng một Welcome node.");
        if (fallbackCount != 1) invalid("Cấu hình phải có đúng một Fallback node.");
        if (nodes.stream().map(DialogNodeInput::priority).distinct().count() != nodes.size()) {
            invalid("Priority của các dialog node phải khác nhau để thứ tự chạy là xác định.");
        }
        for (DialogNodeInput node : nodes) {
            DialogNextStep nextStep = node.nextStep() == null ? DialogNextStep.WAIT_FOR_REPLY : node.nextStep();
            if (node.type() != DialogNodeType.FOLDER
                    && nextStep != DialogNextStep.SKIP_USER_INPUT
                    && nextStep != DialogNextStep.JUMP_TO
                    && isBlank(node.responseText())) {
                invalid("Node ‘" + node.name() + "’ chưa có nội dung phản hồi.");
            }
            if (node.type() == DialogNodeType.ROUTE && isBlank(node.matchIntent())) {
                invalid("Route node ‘" + node.name() + "’ chưa chọn intent.");
            }
            if (!isBlank(node.matchIntent()) && !intentNames.contains(key(node.matchIntent()))) {
                invalid("Node ‘" + node.name() + "’ tham chiếu intent không tồn tại: " + node.matchIntent());
            }
            for (String entityReference : List.of(
                    blankToDefault(node.matchEntityName(), ""),
                    blankToDefault(node.requiredEntityName(), "")
            )) {
                if (!entityReference.isBlank() && !entityNames.contains(key(entityReference))) {
                    invalid("Node ‘" + node.name() + "’ tham chiếu entity không tồn tại: " + entityReference);
                }
            }
            if (nextStep == DialogNextStep.JUMP_TO && isBlank(node.jumpToNode())) {
                invalid("Node ‘" + node.name() + "’ dùng Jump to nhưng chưa chọn node đích.");
            }
            if (!isBlank(node.jumpToNode()) && !nodeNames.contains(key(node.jumpToNode()))) {
                invalid("Node ‘" + node.name() + "’ tham chiếu node đích không tồn tại: " + node.jumpToNode());
            }
            if (nextStep == DialogNextStep.JUMP_TO && key(node.name()).equals(key(node.jumpToNode()))) {
                invalid("Node ‘" + node.name() + "’ không thể Jump to chính nó.");
            }
            if (!isBlank(node.parentNode()) && !nodeNames.contains(key(node.parentNode()))) {
                invalid("Node ‘" + node.name() + "’ có node cha không tồn tại: " + node.parentNode());
            }
            if (!isBlank(node.parentNode()) && key(node.name()).equals(key(node.parentNode()))) {
                invalid("Node ‘" + node.name() + "’ không thể là node cha của chính nó.");
            }
            if ((node.type() == DialogNodeType.WELCOME || node.type() == DialogNodeType.FALLBACK)
                    && !isBlank(node.parentNode())) {
                invalid("Welcome và Fallback phải nằm ở cấp gốc.");
            }
        }
        validateParentCycles(nodes);
    }

    private void validateParentCycles(List<DialogNodeInput> nodes) {
        Map<String, String> parentByNode = new HashMap<>();
        for (DialogNodeInput node : nodes) {
            if (!isBlank(node.parentNode())) parentByNode.put(key(node.name()), key(node.parentNode()));
        }
        for (String nodeName : parentByNode.keySet()) {
            Set<String> path = new HashSet<>();
            String current = nodeName;
            while (current != null) {
                if (!path.add(current)) invalid("Cây Dialog có vòng lặp node cha tại ‘" + current + "’.");
                current = parentByNode.get(current);
            }
        }
    }

    private Set<String> uniqueNames(List<String> names, String label) {
        Set<String> result = new HashSet<>();
        for (String name : names) {
            String key = key(name);
            if (!result.add(key)) invalid("Tên " + label + " bị trùng: " + name);
        }
        return result;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SNAPSHOT_FAILED", "Không thể tạo bản phát hành chatbot.");
        }
    }

    private BotSummaryResponse summary(Bot bot) {
        return new BotSummaryResponse(
                bot.getId(), bot.getName(), bot.getDescription(), bot.getLocale(), bot.getTimezone(),
                bot.isBlueAiCoreEnabled(), bot.getCustomInstructions(),
                bot.getStatus(), bot.getPublicKey(), bot.getDraftRevision(), bot.getPublishedReleaseId(), bot.getUpdatedAt(),
                bot.isActiveForRelease(bot.getPublishedReleaseId()), customization(bot)
        );
    }

    private CustomizationInput customization(Bot bot) {
        return new CustomizationInput(
                bot.getIdentityRole(), bot.getTonePreset(), bot.getAnswerLength(), bot.getResponseStructure(),
                bot.getProactivity(), bot.getCitationPolicy(), bot.getNoSourcePolicy(), bot.getPreferredTerms(),
                bot.getForbiddenTerms(), bot.getFallbackMessage(), bot.getEscalationMessage(), bot.getResponseProfile()
        ).withDefaults();
    }

    private CustomizationInput customization(CustomizationInput value) {
        return (value == null ? CustomizationInput.defaults() : value).withDefaults();
    }

    private BotSnapshot readJson(String value) {
        try {
            return objectMapper.readValue(value, BotSnapshot.class);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SNAPSHOT_INVALID", "Bản phát hành BlueAI không hợp lệ.");
        }
    }

    private String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private void activateBot(Bot selected) {
        botRepository.findAllByOrderByUpdatedAtDesc().forEach(bot -> {
            if (!bot.getId().equals(selected.getId())) bot.deactivate();
        });
        selected.activate();
    }

    private void invalid(String message) {
        throw new ApiException(HttpStatus.BAD_REQUEST, "BOT_CONFIGURATION_INVALID", message);
    }

    private String key(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private String blankToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private List<IntentInput> starterIntents() {
        return List.of(
                new IntentInput("greeting", "Các lời chào", List.of("xin chào", "chào bạn", "hello", "hi", "bạn ơi")),
                new IntentInput("store_hours", "Hỏi giờ mở cửa theo chi nhánh", List.of(
                        "mấy giờ cửa hàng mở cửa", "chi nhánh mấy giờ đóng cửa", "giờ làm việc thế nào",
                        "hôm nay shop mở đến mấy giờ", "cho tôi hỏi giờ hoạt động", "cửa hàng còn mở không"
                ))
        );
    }

    private List<EntityInput> starterEntities() {
        return List.of(new EntityInput(
                "location",
                "Chi nhánh hoặc thành phố",
                List.of(
                        new EntityValueInput("Hà Nội", List.of("HN", "thủ đô")),
                        new EntityValueInput("HCM", List.of("TP.HCM", "Hồ Chí Minh", "Sài Gòn", "SG")),
                        new EntityValueInput("Đà Nẵng", List.of("DN"))
                )
        ));
    }

    private List<DialogNodeInput> starterNodes() {
        return List.of(
                new DialogNodeInput(
                        "Welcome", DialogNodeType.WELCOME, null, null, null, null, null,
                        "Xin chào! Mình có thể giúp bạn tra cứu giờ mở cửa theo chi nhánh.", null,
                        DialogNextStep.WAIT_FOR_REPLY, null, null, 0
                ),
                new DialogNodeInput(
                        "Greeting", DialogNodeType.ROUTE, null, "greeting", null, null, null,
                        "Chào bạn! Bạn muốn hỏi giờ mở cửa của chi nhánh nào?", null,
                        DialogNextStep.WAIT_FOR_REPLY, null, null, 10
                ),
                new DialogNodeInput(
                        "Shop hours", DialogNodeType.ROUTE, null, "store_hours", null, null, null,
                        "", null,
                        DialogNextStep.SKIP_USER_INPUT, null, null, 20
                ),
                new DialogNodeInput(
                        "Hà Nội hours", DialogNodeType.ROUTE, "Shop hours", "store_hours", "location", "Hà Nội", null,
                        "Chi nhánh Hà Nội mở cửa từ 08:00 đến 21:00 mỗi ngày.", null,
                        DialogNextStep.WAIT_FOR_REPLY, null, null, 30
                ),
                new DialogNodeInput(
                        "HCM hours", DialogNodeType.ROUTE, "Shop hours", "store_hours", "location", "HCM", null,
                        "Chi nhánh HCM mở cửa từ 08:00 đến 22:00 mỗi ngày.", null,
                        DialogNextStep.WAIT_FOR_REPLY, null, null, 40
                ),
                new DialogNodeInput(
                        "Đà Nẵng hours", DialogNodeType.ROUTE, "Shop hours", "store_hours", "location", "Đà Nẵng", null,
                        "Chi nhánh Đà Nẵng mở cửa từ 08:30 đến 21:00 mỗi ngày.", null,
                        DialogNextStep.WAIT_FOR_REPLY, null, null, 50
                ),
                new DialogNodeInput(
                        "Ask location", DialogNodeType.ROUTE, "Shop hours", "store_hours", null, null, "location",
                        "Chi nhánh {{location}} hiện chưa có lịch hoạt động được cấu hình.",
                        "Bạn muốn hỏi giờ mở cửa của chi nhánh nào?",
                        DialogNextStep.WAIT_FOR_REPLY, null,
                        "Mình chưa nhận ra chi nhánh đó. Bạn hãy chọn Hà Nội, HCM hoặc Đà Nẵng nhé.", 90
                ),
                new DialogNodeInput(
                        "Anything else", DialogNodeType.FALLBACK, null, null, null, null, null,
                        "Mình chưa hiểu yêu cầu. Hiện tại bạn có thể hỏi giờ mở cửa của Hà Nội, HCM hoặc Đà Nẵng.", null,
                        DialogNextStep.WAIT_FOR_REPLY, null, null, 999
                )
        );
    }
}
