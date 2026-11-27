package com.blueai.studio.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.studio.dto.BotStudioDtos.DialogNodeInput;
import com.blueai.studio.dto.BotStudioDtos.EntityInput;
import com.blueai.studio.dto.BotStudioDtos.EntityValueInput;
import com.blueai.studio.model.DialogNextStep;
import com.blueai.studio.model.DialogNodeType;
import org.springframework.http.HttpStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure, stateless checks that complement BotStudioService's schema validation.
 * Call validateOrThrow(nodes, entities) after its existing reference checks;
 * analyze(nodes, entities) returns immutable diagnostics suitable for previews.
 * Warnings never prevent saving. No database, runtime service or provider is used.
 */
public final class DialogConfigurationValidator {

    public static final String INVALID_CONFIGURATION_CODE = "BOT_CONFIGURATION_INVALID";
    // The runtime counts every executed node, including the final WAIT node.
    // Keep aligned with DialogRuntimeService.MAX_AUTOMATIC_STEPS.
    public static final int MAX_AUTOMATIC_STEPS = 20;

    public DialogConfigurationValidator() {
    }

    private void validateTranslation(DialogNodeInput node, String original, String translated, List<Diagnostic> diagnostics) {
        if (translated == null || translated.isBlank()) return;
        if (!templateVariables(original).equals(templateVariables(translated))) {
            error(diagnostics, "ENGLISH_TEMPLATE_VARIABLES", node,
                    "Bản tiếng Anh của bước '" + node.name() + "' phải giữ nguyên các biến {{entity}} của bản gốc.", List.of(node.name()));
        }
    }

    private Set<String> templateVariables(String text) {
        Set<String> variables = new HashSet<>();
        var matcher = java.util.regex.Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}").matcher(text == null ? "" : text);
        while (matcher.find()) variables.add(matcher.group());
        return variables;
    }

    private void validateTranslations(DialogNodeInput node, List<Diagnostic> diagnostics) {
        if (node.translations().size() > 16) {
            error(diagnostics, "TOO_MANY_TRANSLATIONS", node, "Mỗi bước hỗ trợ tối đa 16 bản dịch.", List.of(node.name()));
        }
        node.translations().forEach((locale, reply) -> {
            if (locale == null || !locale.matches("[a-z]{2,3}(?:-[A-Z]{2})?") || "vi".equals(locale) || reply == null) {
                error(diagnostics, "INVALID_TRANSLATION_LOCALE", node, "Mã ngôn ngữ bản dịch không hợp lệ; nội dung gốc dùng tiếng Việt.", List.of(node.name()));
                return;
            }
            validateLocalizedField(node, locale, node.responseText(), reply.responseText(), 4000, diagnostics);
            validateLocalizedField(node, locale, node.missingEntityPrompt(), reply.missingEntityPrompt(), 1000, diagnostics);
            validateLocalizedField(node, locale, node.irrelevantResponseText(), reply.irrelevantResponseText(), 1000, diagnostics);
        });
    }

    private void validateLocalizedField(DialogNodeInput node, String locale, String original, String translated,
                                        int maxLength, List<Diagnostic> diagnostics) {
        if (translated == null || translated.isBlank()) return;
        if (translated.length() > maxLength || !templateVariables(original).equals(templateVariables(translated))) {
            error(diagnostics, "LOCALIZED_TEMPLATE_INVALID", node,
                    "Bản dịch " + locale + " phải giữ nguyên biến {{entity}} và nằm trong giới hạn độ dài.", List.of(node.name()));
        }
    }

    public enum Severity { ERROR, WARNING }

    public record Diagnostic(
            Severity severity, String code, String nodeName, String message, List<String> relatedNodes
    ) {
        public Diagnostic {
            relatedNodes = List.copyOf(relatedNodes);
        }
    }

    public void validate(List<DialogNodeInput> nodes, List<EntityInput> entities) {
        validateOrThrow(nodes, entities);
    }

    public void validateOrThrow(List<DialogNodeInput> nodes, List<EntityInput> entities) {
        analyze(nodes, entities).stream()
                .filter(diagnostic -> diagnostic.severity() == Severity.ERROR)
                .findFirst()
                .ifPresent(diagnostic -> {
                    throw new ApiException(HttpStatus.BAD_REQUEST, INVALID_CONFIGURATION_CODE, diagnostic.message());
                });
    }

    public List<Diagnostic> analyze(List<DialogNodeInput> nodes, List<EntityInput> entities) {
        List<Diagnostic> diagnostics = new ArrayList<>();
        if (nodes == null || entities == null) {
            error(diagnostics, "CONFIGURATION_REQUIRED", null, "Dialog nodes and entities are required.", List.of());
            return List.copyOf(diagnostics);
        }
        Map<String, Set<String>> entityValues = new LinkedHashMap<>();
        for (EntityInput entity : entities) {
            if (entity == null || key(entity.name()).isEmpty()) continue;
            Set<String> values = new LinkedHashSet<>();
            if (entity.values() != null) {
                for (EntityValueInput value : entity.values()) {
                    if (value != null && !key(value.value()).isEmpty()) values.add(key(value.value()));
                }
            }
            entityValues.put(key(entity.name()), values);
        }
        Map<String, DialogNodeInput> byName = new LinkedHashMap<>();
        for (DialogNodeInput node : nodes) {
            if (node == null || key(node.name()).isEmpty() || node.type() == null) {
                error(diagnostics, "INVALID_DIALOG_NODE", null, "Every dialog node needs a name and type.", List.of());
                continue;
            }
            if (byName.putIfAbsent(key(node.name()), node) != null) {
                error(diagnostics, "DUPLICATE_NODE_NAME", node, "Duplicate dialog node name: " + node.name(), List.of(node.name()));
            }
            String entityName = key(node.matchEntityName());
            String entityValue = key(node.matchEntityValue());
            if (!entityValue.isEmpty() && entityName.isEmpty()) {
                error(diagnostics, "MATCH_ENTITY_REQUIRED", node,
                        "Node '" + node.name() + "' specifies an entity value without an entity.", List.of(node.name()));
            } else if (!entityName.isEmpty() && !entityValues.containsKey(entityName)) {
                error(diagnostics, "UNKNOWN_MATCH_ENTITY", node,
                        "Node '" + node.name() + "' references unknown entity @" + node.matchEntityName() + ".", List.of(node.name()));
            } else if (!entityValue.isEmpty() && !entityValues.get(entityName).contains(entityValue)) {
                error(diagnostics, "UNKNOWN_ENTITY_VALUE", node,
                        "Node '" + node.name() + "' matches value '" + node.matchEntityValue()
                                + "' which is not a canonical value of @" + node.matchEntityName() + ".", List.of(node.name()));
            }
            if (!key(node.requiredEntityName()).isEmpty() && !entityValues.containsKey(key(node.requiredEntityName()))) {
                error(diagnostics, "UNKNOWN_REQUIRED_ENTITY", node,
                        "Node '" + node.name() + "' requires unknown entity @" + node.requiredEntityName() + ".", List.of(node.name()));
            }
        }
        for (DialogNodeInput node : byName.values()) {
            if (node.translations() == null) {
                validateTranslation(node, node.responseText(), node.englishResponseText(), diagnostics);
                validateTranslation(node, node.missingEntityPrompt(), node.englishMissingEntityPrompt(), diagnostics);
                validateTranslation(node, node.irrelevantResponseText(), node.englishIrrelevantResponseText(), diagnostics);
            } else {
                validateTranslations(node, diagnostics);
            }
            if (!key(node.parentNode()).isEmpty() && !byName.containsKey(key(node.parentNode()))) {
                error(diagnostics, "UNKNOWN_PARENT_NODE", node,
                        "Node '" + node.name() + "' references unknown parent '" + node.parentNode() + "'.", List.of(node.name()));
            }
            if (effectiveNextStep(node) == DialogNextStep.JUMP_TO && !byName.containsKey(key(node.jumpToNode()))) {
                error(diagnostics, "UNKNOWN_JUMP_TARGET", node,
                        "Node '" + node.name() + "' needs an existing Jump to target.", List.of(node.name()));
            }
        }
        checkParentCycles(byName, diagnostics);
        if (diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == Severity.ERROR)) {
            new FlowAnalysis(byName, entityValues, diagnostics).run();
            checkOverlappingSiblings(byName.values().stream().toList(), diagnostics);
        }
        return List.copyOf(diagnostics);
    }

    private static void checkParentCycles(Map<String, DialogNodeInput> nodes, List<Diagnostic> diagnostics) {
        Set<String> completed = new HashSet<>();
        for (String name : nodes.keySet()) {
            List<String> path = new ArrayList<>();
            Set<String> visited = new HashSet<>();
            String current = name;
            while (nodes.containsKey(current) && !completed.contains(current)) {
                if (!visited.add(current)) {
                    List<String> cycle = path.subList(path.indexOf(current), path.size()).stream()
                            .map(item -> nodes.get(item).name()).toList();
                    error(diagnostics, "PARENT_CYCLE", nodes.get(current),
                            "Dialog parent cycle: " + String.join(" -> ", cycle) + " -> " + nodes.get(current).name(), cycle);
                    break;
                }
                path.add(current);
                current = key(nodes.get(current).parentNode());
            }
            completed.addAll(path);
        }
    }

    private static void checkOverlappingSiblings(List<DialogNodeInput> nodes, List<Diagnostic> diagnostics) {
        List<DialogNodeInput> routes = nodes.stream().filter(node -> node.type() == DialogNodeType.ROUTE)
                .sorted(Comparator.comparingInt(DialogNodeInput::priority)).toList();
        for (int i = 0; i < routes.size(); i++) {
            DialogNodeInput earlier = routes.get(i);
            for (int j = i + 1; j < routes.size(); j++) {
                DialogNodeInput later = routes.get(j);
                if (!key(earlier.parentNode()).equals(key(later.parentNode()))
                        || !key(earlier.matchIntent()).equals(key(later.matchIntent()))) continue;
                String leftEntity = key(earlier.matchEntityName());
                String rightEntity = key(later.matchEntityName());
                String leftValue = key(earlier.matchEntityValue());
                String rightValue = key(later.matchEntityValue());
                // Specific branches followed by a collector for the same slot
                // are the starter template's intentional fallback pattern.
                if (!leftEntity.isEmpty() && rightEntity.isEmpty()
                        && leftEntity.equals(key(later.requiredEntityName()))) continue;
                if (!leftEntity.isEmpty() && leftEntity.equals(rightEntity)
                        && !leftValue.isEmpty() && !rightValue.isEmpty() && !leftValue.equals(rightValue)) continue;
                boolean shadowed = leftEntity.isEmpty() || (leftEntity.equals(rightEntity)
                        && (leftValue.isEmpty() || leftValue.equals(rightValue)));
                diagnostics.add(new Diagnostic(Severity.WARNING, shadowed ? "SHADOWED_ROUTE" : "OVERLAPPING_ROUTES", later.name(),
                        "Sibling route '" + later.name() + "' " + (shadowed ? "is shadowed by" : "overlaps with")
                                + " earlier route '" + earlier.name() + "'. Review priority and conditions; Jump to can still enter either route.",
                        List.of(earlier.name(), later.name())));
            }
        }
    }

    /**
     * Symbolic execution keeps each intent/entity assignment consistent for an
     * entire turn. Candidate failure is carried to lower-priority candidates,
     * so impossible combinations and shadowed branches cannot invent a cycle.
     */
    private static final class FlowAnalysis {
        private final Map<String, DialogNodeInput> nodes;
        private final Map<String, List<DialogNodeInput>> children = new LinkedHashMap<>();
        private final Map<String, List<Guard>> eligibility = new LinkedHashMap<>();
        private final List<Diagnostic> diagnostics;
        private final Set<String> reported = new HashSet<>();
        private final Set<String> jumpTargets = new HashSet<>();
        private final State initial;
        private final State welcomeState;
        private final DialogNodeInput fallback;

        private FlowAnalysis(Map<String, DialogNodeInput> nodes, Map<String, Set<String>> entityValues, List<Diagnostic> diagnostics) {
            this.nodes = nodes;
            this.diagnostics = diagnostics;
            Set<String> intents = new LinkedHashSet<>();
            intents.add(""); // No recognized intent; distinct from every route intent.
            for (DialogNodeInput node : nodes.values()) {
                if (!key(node.matchIntent()).isEmpty()) intents.add(key(node.matchIntent()));
                children.computeIfAbsent(key(node.parentNode()), ignored -> new ArrayList<>()).add(node);
                if (effectiveNextStep(node) == DialogNextStep.JUMP_TO) jumpTargets.add(key(node.jumpToNode()));
            }
            children.values().forEach(group -> group.sort(Comparator.comparingInt(DialogNodeInput::priority)));
            Map<String, Set<String>> domains = new LinkedHashMap<>();
            entityValues.forEach((name, values) -> {
                Set<String> domain = new LinkedHashSet<>(values);
                domain.add(""); // Missing entity; canonical values are nonblank.
                domains.put(name, Set.copyOf(domain));
            });
            initial = new State(Set.copyOf(intents), Map.copyOf(domains));
            Map<String, Set<String>> emptyVariables = new LinkedHashMap<>();
            domains.keySet().forEach(name -> emptyVariables.put(name, Set.of("")));
            welcomeState = new State(Set.of(""), Map.copyOf(emptyVariables));
            fallback = children.getOrDefault("", List.of()).stream()
                    .filter(node -> node.type() == DialogNodeType.FALLBACK).findFirst().orElse(null);
            nodes.values().forEach(node -> eligibility.put(key(node.name()), guardsFor(node)));
        }

        private List<Guard> guardsFor(DialogNodeInput node) {
            if (node.type() == DialogNodeType.ROUTE) {
                return key(node.matchIntent()).isEmpty() ? List.of()
                        : List.of(new Guard(key(node.matchIntent()), key(node.matchEntityName()), key(node.matchEntityValue())));
            }
            if (node.type() != DialogNodeType.FOLDER) return List.of();
            Set<Guard> guards = new LinkedHashSet<>();
            ArrayDeque<DialogNodeInput> pending = new ArrayDeque<>();
            pending.add(node);
            while (!pending.isEmpty()) {
                DialogNodeInput folder = pending.removeFirst();
                for (DialogNodeInput child : children.getOrDefault(key(folder.name()), List.of())) {
                    if (child.type() == DialogNodeType.FOLDER) pending.add(child);
                    else if (child.type() == DialogNodeType.ROUTE) guards.addAll(guardsFor(child));
                }
            }
            return List.copyOf(guards);
        }

        private void run() {
            for (DialogNodeInput node : nodes.values()) {
                List<State> entries;
                if (jumpTargets.contains(key(node.name())) || node.type() == DialogNodeType.FALLBACK) {
                    entries = List.of(initial);
                } else if (node.type() == DialogNodeType.WELCOME) {
                    // A fresh welcome has neither an intent nor supplied slots.
                    entries = List.of(welcomeState);
                } else {
                    entries = select(List.of(initial), eligibility.get(key(node.name()))).matching();
                }
                for (State entry : entries) walk(node, entry, List.of());
            }
        }

        private void walk(DialogNodeInput node, State state, List<String> previous) {
            // Missing slots pause, but a supplied slot does not. Only analyze
            // outgoing transitions for assignments where the slot is present.
            state = state.withEntity(key(node.requiredEntityName()), "", true);
            if (state == null || effectiveNextStep(node) == DialogNextStep.WAIT_FOR_REPLY) return;
            List<String> path = new ArrayList<>(previous);
            path.add(key(node.name()));
            for (Transition transition : transitions(node, state)) {
                String target = key(transition.target().name());
                int repeatedAt = path.indexOf(target);
                if (repeatedAt >= 0) {
                    List<String> cycle = new ArrayList<>(path.subList(repeatedAt, path.size()));
                    String signature = "cycle:" + cycle.stream().sorted().toList();
                    cycle.add(target);
                    report(signature, "AUTOMATIC_CYCLE", node, cycle,
                            "Automatic dialog cycle must pause at a WAIT_FOR_REPLY node: ");
                } else if (path.size() >= MAX_AUTOMATIC_STEPS) {
                    List<String> overflow = new ArrayList<>(path);
                    overflow.add(target);
                    report("depth:" + path.getFirst(), "AUTOMATIC_DEPTH_LIMIT", nodes.get(path.getFirst()), overflow,
                            "Dialog path exceeds the runtime limit of " + MAX_AUTOMATIC_STEPS + " nodes without a guaranteed pause: ");
                } else {
                    walk(transition.target(), transition.state(), path);
                }
            }
        }

        private void report(String signature, String code, DialogNodeInput node, List<String> path, String prefix) {
            if (!reported.add(signature)) return;
            List<String> names = path.stream().map(name -> nodes.get(name).name()).toList();
            error(diagnostics, code, node, prefix + String.join(" -> ", names), names);
        }

        private List<Transition> transitions(DialogNodeInput current, State state) {
            if (effectiveNextStep(current) == DialogNextStep.JUMP_TO) {
                return List.of(new Transition(nodes.get(key(current.jumpToNode())), state));
            }
            List<DialogNodeInput> candidates = new ArrayList<>(children.getOrDefault(key(current.name()), List.of()));
            children.getOrDefault(key(current.parentNode()), List.of()).stream()
                    .filter(node -> node.type() == DialogNodeType.ROUTE && node.priority() > current.priority())
                    .forEach(candidates::add);
            List<State> remaining = List.of(state);
            Set<Transition> transitions = new LinkedHashSet<>();
            for (DialogNodeInput candidate : candidates) {
                Selection selection = select(remaining, eligibility.get(key(candidate.name())));
                selection.matching().forEach(match -> transitions.add(new Transition(candidate, match)));
                remaining = selection.remaining();
                if (remaining.isEmpty()) break;
            }
            if (fallback != null) remaining.forEach(match -> transitions.add(new Transition(fallback, match)));
            return List.copyOf(transitions);
        }
    }

    private record Guard(String intent, String entity, String value) { }
    private record Transition(DialogNodeInput target, State state) { }
    private record Selection(List<State> matching, List<State> remaining) { }

    private static Selection select(List<State> states, List<Guard> guards) {
        Set<State> matching = new LinkedHashSet<>();
        List<State> remaining = states;
        for (Guard guard : guards) {
            Set<State> rejected = new LinkedHashSet<>();
            for (State state : remaining) {
                State sameIntent = state.withIntent(guard.intent(), true);
                State otherIntent = state.withIntent(guard.intent(), false);
                if (otherIntent != null) rejected.add(otherIntent);
                if (sameIntent == null) continue;
                State accepted = sameIntent.withEntity(guard.entity(), guard.value(), true);
                if (accepted != null) matching.add(accepted);
                if (!guard.entity().isEmpty()) {
                    State otherEntity = sameIntent.withEntity(guard.entity(), guard.value(), false);
                    if (otherEntity != null) rejected.add(otherEntity);
                }
            }
            remaining = List.copyOf(rejected);
        }
        return new Selection(List.copyOf(matching), remaining);
    }

    private record State(Set<String> intents, Map<String, Set<String>> entities) {
        private State withIntent(String intent, boolean matches) {
            Set<String> allowed = new LinkedHashSet<>(intents);
            if (matches) allowed.retainAll(Set.of(intent));
            else allowed.remove(intent);
            return allowed.isEmpty() ? null : new State(Set.copyOf(allowed), entities);
        }

        private State withEntity(String entity, String value, boolean matches) {
            if (entity.isEmpty()) return this;
            Set<String> allowed = new LinkedHashSet<>(entities.getOrDefault(entity, Set.of("")));
            allowed.removeIf(candidate -> matches != (!candidate.isEmpty() && (value.isEmpty() || candidate.equals(value))));
            if (allowed.isEmpty()) return null;
            Map<String, Set<String>> updated = new LinkedHashMap<>(entities);
            updated.put(entity, Set.copyOf(allowed));
            return new State(intents, Map.copyOf(updated));
        }
    }

    private static DialogNextStep effectiveNextStep(DialogNodeInput node) {
        if (node.type() == DialogNodeType.FOLDER) return DialogNextStep.SKIP_USER_INPUT;
        return node.nextStep() == null ? DialogNextStep.WAIT_FOR_REPLY : node.nextStep();
    }

    private static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void error(List<Diagnostic> diagnostics, String code, DialogNodeInput node, String message, List<String> related) {
        diagnostics.add(new Diagnostic(Severity.ERROR, code, node == null ? null : node.name(), message, related));
    }
}
