package com.blueai.studio.service;

import com.blueai.studio.dto.BotStudioDtos.DialogNodeInput;
import com.blueai.studio.dto.BotStudioDtos.EntityInput;
import com.blueai.studio.dto.BotStudioDtos.EntityValueInput;
import com.blueai.studio.dto.BotStudioDtos.IntentInput;
import com.blueai.studio.dto.BotStudioDtos.PreviewContext;
import com.blueai.studio.dto.BotStudioDtos.RuntimeResponse;
import com.blueai.studio.model.DialogNextStep;
import com.blueai.studio.model.DialogNodeType;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class DialogRuntimeService {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+", Pattern.CASE_INSENSITIVE);
    private static final double INTENT_THRESHOLD = 0.42;
    private static final int MAX_AUTOMATIC_STEPS = 20;

    public DialogRuntimeService() {
    }

    public RuntimeResponse welcome(BotSnapshot snapshot) {
        DialogNodeInput welcome = snapshot.dialogNodes().stream()
                .filter(node -> node.type() == DialogNodeType.WELCOME)
                .min(Comparator.comparingInt(DialogNodeInput::priority))
                .orElse(null);
        if (welcome == null) {
            return new RuntimeResponse("Xin chào! Mình có thể giúp gì cho bạn?", null, Map.of(), null, emptyContext(), false);
        }
        return respondFromNode(snapshot, welcome, null, Map.of(), Map.of(), null, false);
    }

    public RuntimeResponse evaluate(BotSnapshot snapshot, PreviewContext suppliedContext, String message) {
        PreviewContext current = suppliedContext == null ? emptyContext() : suppliedContext;
        String normalizedMessage = normalize(message);
        if (Set.of("huy", "cancel", "bat dau lai", "restart", "reset").contains(normalizedMessage)) {
            return new RuntimeResponse(
                    "Đã hủy yêu cầu hiện tại. Bạn muốn mình hỗ trợ việc gì tiếp theo?",
                    null,
                    Map.of(),
                    null,
                    emptyContext(),
                    false
            );
        }

        Map<String, String> recognizedEntities = extractEntities(snapshot.entities(), normalizedMessage);
        String classifiedIntent = classifyIntent(snapshot.intents(), normalizedMessage);
        Map<String, String> variables = new HashMap<>(current.variables());
        variables.putAll(recognizedEntities);

        if (current.pendingEntity() != null && !recognizedEntities.containsKey(current.pendingEntity())) {
            boolean changedTopic = classifiedIntent != null
                    && !classifiedIntent.equalsIgnoreCase(current.pendingIntent());
            if (!changedTopic) {
                DialogNodeInput pendingNode = findNodeByName(snapshot.dialogNodes(), current.waitingNode());
                if (pendingNode == null) {
                    pendingNode = findPromptNode(snapshot.dialogNodes(), current.pendingIntent(), current.pendingEntity());
                }
                String prompt = classifiedIntent == null && pendingNode != null && !isBlank(pendingNode.irrelevantResponseText())
                        ? pendingNode.irrelevantResponseText()
                        : missingEntityPrompt(pendingNode, current.pendingEntity());
                return new RuntimeResponse(
                        render(prompt, variables),
                        current.pendingIntent(),
                        recognizedEntities,
                        pendingNode == null ? null : pendingNode.name(),
                        new PreviewContext(
                                current.pendingIntent(),
                                current.pendingEntity(),
                                current.lastIntent(),
                                variables,
                                pendingNode == null ? current.waitingNode() : pendingNode.name()
                        ),
                        classifiedIntent == null
                );
            }
        }

        DialogNodeInput waitingNode = findNodeByName(snapshot.dialogNodes(), current.waitingNode());
        if (current.pendingEntity() == null
                && classifiedIntent == null
                && recognizedEntities.isEmpty()
                && waitingNode != null
                && !isBlank(waitingNode.irrelevantResponseText())) {
            return new RuntimeResponse(
                    render(waitingNode.irrelevantResponseText(), variables),
                    current.lastIntent(),
                    Map.of(),
                    waitingNode.name(),
                    new PreviewContext(null, null, current.lastIntent(), variables, waitingNode.name()),
                    true,
                    true
            );
        }

        String matchedIntent = classifiedIntent;
        if (matchedIntent == null && current.pendingIntent() != null) matchedIntent = current.pendingIntent();
        if (matchedIntent == null && !recognizedEntities.isEmpty()) matchedIntent = current.lastIntent();

        DialogNodeInput matched = findMatchingNode(
                snapshot.dialogNodes(),
                matchedIntent,
                variables,
                current.waitingNode()
        );
        if (matched != null) {
            return respondFromNode(
                    snapshot,
                    matched,
                    matchedIntent,
                    recognizedEntities,
                    variables,
                    matchedIntent,
                    false
            );
        }

        DialogNodeInput fallback = snapshot.dialogNodes().stream()
                .filter(node -> node.type() == DialogNodeType.FALLBACK)
                .min(Comparator.comparingInt(DialogNodeInput::priority))
                .orElse(null);
        if (fallback == null) {
            return new RuntimeResponse(
                    "Mình chưa hiểu yêu cầu này. Bạn thử diễn đạt theo cách khác nhé.",
                    matchedIntent,
                    recognizedEntities,
                    null,
                    new PreviewContext(null, null, current.lastIntent(), variables),
                    true
            );
        }
        return respondFromNode(
                snapshot,
                fallback,
                matchedIntent,
                recognizedEntities,
                variables,
                current.lastIntent(),
                true
        );
    }

    private PreviewContext emptyContext() {
        return new PreviewContext(null, null, null, Map.of());
    }

    private RuntimeResponse respondFromNode(
            BotSnapshot snapshot,
            DialogNodeInput startingNode,
            String matchedIntent,
            Map<String, String> recognizedEntities,
            Map<String, String> variables,
            String lastIntent,
            boolean fallback
    ) {
        List<String> messages = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        DialogNodeInput node = startingNode;
        String finalNode = startingNode.name();
        String effectiveLastIntent = lastIntent;
        boolean effectiveFallback = fallback;

        for (int step = 0; node != null && step < MAX_AUTOMATIC_STEPS; step++) {
            String nodeKey = normalize(node.name());
            if (!visited.add(nodeKey)) break;

            finalNode = node.name();
            effectiveFallback = effectiveFallback || node.type() == DialogNodeType.FALLBACK;
            String nodeIntent = isBlank(node.matchIntent()) ? matchedIntent : node.matchIntent();
            if (!isBlank(nodeIntent)) effectiveLastIntent = nodeIntent;

            if (!isBlank(node.requiredEntityName()) && !variables.containsKey(node.requiredEntityName())) {
                messages.add(missingEntityPrompt(node, node.requiredEntityName()));
                return new RuntimeResponse(
                        String.join("\n\n", messages),
                        matchedIntent,
                        recognizedEntities,
                        finalNode,
                        new PreviewContext(
                                nodeIntent,
                                node.requiredEntityName(),
                                effectiveLastIntent,
                                variables,
                                node.name()
                        ),
                        effectiveFallback
                );
            }

            String renderedResponse = render(node.responseText(), variables);
            if (!isBlank(renderedResponse)) messages.add(renderedResponse);
            DialogNextStep nextStep = node.type() == DialogNodeType.FOLDER
                    ? DialogNextStep.SKIP_USER_INPUT
                    : nextStep(node);
            if (nextStep == DialogNextStep.WAIT_FOR_REPLY) {
                return completedResponse(messages, matchedIntent, recognizedEntities, finalNode, effectiveLastIntent, variables, node.name(), effectiveFallback);
            }

            node = nextStep == DialogNextStep.JUMP_TO
                    ? findNodeByName(snapshot.dialogNodes(), node.jumpToNode())
                    : findNextEligibleNode(snapshot.dialogNodes(), node, matchedIntent, variables);
            if (node == null) {
                return completedResponse(messages, matchedIntent, recognizedEntities, finalNode, effectiveLastIntent, variables, finalNode, effectiveFallback);
            }
        }

        return completedResponse(messages, matchedIntent, recognizedEntities, finalNode, effectiveLastIntent, variables, finalNode, effectiveFallback);
    }

    private RuntimeResponse completedResponse(
            List<String> messages,
            String matchedIntent,
            Map<String, String> recognizedEntities,
            String matchedNode,
            String lastIntent,
            Map<String, String> variables,
            String waitingNode,
            boolean fallback
    ) {
        String message = String.join("\n\n", messages);
        return new RuntimeResponse(
                isBlank(message) ? "Không có nhánh hội thoại phù hợp để tiếp tục." : message,
                matchedIntent,
                recognizedEntities,
                matchedNode,
                new PreviewContext(null, null, lastIntent, variables, waitingNode),
                fallback
        );
    }

    private DialogNodeInput findNextEligibleNode(
            List<DialogNodeInput> nodes,
            DialogNodeInput current,
            String intent,
            Map<String, String> variables
    ) {
        DialogNodeInput child = nodes.stream()
                .filter(node -> sameParent(node.parentNode(), current.name()))
                .filter(node -> node.type() == DialogNodeType.FOLDER
                        ? folderLeadsToMatch(nodes, node, intent, variables, new HashSet<>())
                        : routeMatches(node, intent, variables))
                .min(Comparator.comparingInt(DialogNodeInput::priority))
                .orElse(null);
        if (child != null) return child;

        DialogNodeInput sibling = nodes.stream()
                .filter(node -> sameParent(node.parentNode(), current.parentNode()))
                .filter(node -> node.priority() > current.priority())
                .filter(node -> routeMatches(node, intent, variables))
                .min(Comparator.comparingInt(DialogNodeInput::priority))
                .orElse(null);
        if (sibling != null) return sibling;

        return nodes.stream()
                .filter(node -> node.type() == DialogNodeType.FALLBACK)
                .filter(node -> isBlank(node.parentNode()))
                .min(Comparator.comparingInt(DialogNodeInput::priority))
                .orElse(null);
    }

    private DialogNodeInput findNodeByName(List<DialogNodeInput> nodes, String name) {
        if (isBlank(name)) return null;
        return nodes.stream()
                .filter(node -> node.name().equalsIgnoreCase(name.trim()))
                .findFirst()
                .orElse(null);
    }

    private String missingEntityPrompt(DialogNodeInput node, String entityName) {
        return node == null || isBlank(node.missingEntityPrompt())
                ? "Bạn vui lòng cho biết " + entityName + "."
                : node.missingEntityPrompt();
    }

    private DialogNextStep nextStep(DialogNodeInput node) {
        return node.nextStep() == null ? DialogNextStep.WAIT_FOR_REPLY : node.nextStep();
    }

    private DialogNodeInput findMatchingNode(
            List<DialogNodeInput> nodes,
            String intent,
            Map<String, String> variables,
            String waitingNode
    ) {
        if (intent == null) return null;
        DialogNodeInput childMatch = nodes.stream()
                .filter(node -> sameParent(node.parentNode(), waitingNode))
                .filter(node -> routeMatches(node, intent, variables)
                        || (node.type() == DialogNodeType.FOLDER
                        && folderLeadsToMatch(nodes, node, intent, variables, new HashSet<>())))
                .sorted(Comparator.comparingInt(DialogNodeInput::priority))
                .findFirst()
                .orElse(null);
        if (childMatch != null) return childMatch;

        return nodes.stream()
                .filter(node -> isBlank(node.parentNode()))
                .filter(node -> routeMatches(node, intent, variables))
                .min(Comparator.comparingInt(DialogNodeInput::priority))
                .orElse(null);
    }

    private boolean folderLeadsToMatch(
            List<DialogNodeInput> nodes,
            DialogNodeInput folder,
            String intent,
            Map<String, String> variables,
            Set<String> visited
    ) {
        if (!visited.add(normalize(folder.name()))) return false;
        return nodes.stream()
                .filter(node -> sameParent(node.parentNode(), folder.name()))
                .anyMatch(node -> routeMatches(node, intent, variables)
                        || (node.type() == DialogNodeType.FOLDER
                        && folderLeadsToMatch(nodes, node, intent, variables, visited)));
    }

    private boolean sameParent(String left, String right) {
        return normalize(left).equals(normalize(right));
    }

    private boolean routeMatches(DialogNodeInput node, String intent, Map<String, String> variables) {
        if (node.type() != DialogNodeType.ROUTE || intent == null || !intent.equalsIgnoreCase(node.matchIntent())) {
            return false;
        }
        if (isBlank(node.matchEntityName())) return true;
        String value = variables.get(node.matchEntityName());
        return value != null && (isBlank(node.matchEntityValue()) || value.equalsIgnoreCase(node.matchEntityValue()));
    }

    private DialogNodeInput findPromptNode(List<DialogNodeInput> nodes, String intent, String entityName) {
        if (intent == null) return null;
        return nodes.stream()
                .filter(node -> node.type() == DialogNodeType.ROUTE)
                .filter(node -> intent.equalsIgnoreCase(node.matchIntent()))
                .filter(node -> entityName.equalsIgnoreCase(node.requiredEntityName()))
                .min(Comparator.comparingInt(DialogNodeInput::priority))
                .orElse(null);
    }

    private String classifyIntent(List<IntentInput> intents, String normalizedMessage) {
        String bestIntent = null;
        double bestScore = 0;
        for (IntentInput intent : intents) {
            for (String example : intent.examples()) {
                double score = similarity(normalizedMessage, normalize(example));
                if (score > bestScore) {
                    bestScore = score;
                    bestIntent = intent.name();
                }
            }
        }
        return bestScore >= INTENT_THRESHOLD ? bestIntent : null;
    }

    private Map<String, String> extractEntities(List<EntityInput> entities, String normalizedMessage) {
        Map<String, String> result = new LinkedHashMap<>();
        for (EntityInput entity : entities) {
            List<AliasCandidate> candidates = new ArrayList<>();
            for (EntityValueInput value : entity.values()) {
                List<String> aliases = new ArrayList<>();
                aliases.add(value.value());
                if (value.synonyms() != null) aliases.addAll(value.synonyms());
                for (String alias : aliases) {
                    String normalizedAlias = normalize(alias);
                    if (!normalizedAlias.isBlank() && containsPhrase(normalizedMessage, normalizedAlias)) {
                        candidates.add(new AliasCandidate(value.value(), normalizedAlias.length()));
                    }
                }
            }
            candidates.stream()
                    .max(Comparator.comparingInt(AliasCandidate::length))
                    .ifPresent(candidate -> result.put(entity.name(), candidate.canonicalValue()));
        }
        return result;
    }

    private boolean containsPhrase(String message, String alias) {
        return (" " + message + " ").contains(" " + alias + " ");
    }

    private double similarity(String left, String right) {
        if (left.equals(right)) return 1;
        if (left.length() >= 4 && right.length() >= 4 && (left.contains(right) || right.contains(left))) return 0.88;
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0;
        int overlap = 0;
        for (String token : leftTokens) if (rightTokens.contains(token)) overlap++;
        return (2.0 * overlap) / (leftTokens.size() + rightTokens.size());
    }

    private Set<String> tokens(String input) {
        Set<String> result = new HashSet<>();
        for (String token : input.split(" ")) if (token.length() > 1) result.add(token);
        return result;
    }

    private String render(String template, Map<String, String> variables) {
        String rendered = template == null ? "" : template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return rendered;
    }

    static String normalize(String input) {
        String value = input == null ? "" : input.trim().toLowerCase(Locale.ROOT).replace('đ', 'd');
        value = COMBINING_MARKS.matcher(Normalizer.normalize(value, Normalizer.Form.NFD)).replaceAll("");
        return NON_WORD.matcher(value).replaceAll(" ").trim().replaceAll("\\s+", " ");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String blankToDefault(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value;
    }

    private record AliasCandidate(String canonicalValue, int length) {
    }
}
