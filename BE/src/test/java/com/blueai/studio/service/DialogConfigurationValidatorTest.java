package com.blueai.studio.service;

import com.blueai.auth.exception.ApiException;
import com.blueai.studio.dto.BotStudioDtos.DialogNodeInput;
import com.blueai.studio.dto.BotStudioDtos.EntityInput;
import com.blueai.studio.dto.BotStudioDtos.EntityValueInput;
import com.blueai.studio.model.DialogNextStep;
import com.blueai.studio.model.DialogNodeType;
import com.blueai.studio.service.DialogConfigurationValidator.Diagnostic;
import com.blueai.studio.service.DialogConfigurationValidator.Severity;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static com.blueai.studio.model.DialogNextStep.JUMP_TO;
import static com.blueai.studio.model.DialogNextStep.SKIP_USER_INPUT;
import static com.blueai.studio.model.DialogNextStep.WAIT_FOR_REPLY;
import static com.blueai.studio.model.DialogNodeType.FALLBACK;
import static com.blueai.studio.model.DialogNodeType.FOLDER;
import static com.blueai.studio.model.DialogNodeType.ROUTE;
import static com.blueai.studio.model.DialogNodeType.WELCOME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DialogConfigurationValidatorTest {

    private final DialogConfigurationValidator validator = new DialogConfigurationValidator();
    private final List<EntityInput> entities = List.of(entity("location", "Hà Nội", "HCM", "Đà Nẵng"));

    @Test
    void acceptsCanonicalValueWithCaseAndSurroundingWhitespaceNormalized() {
        DialogNodeInput node = conditioned("Hours", null, "LOCATION", "  hÀ nỘi  ", WAIT_FOR_REPLY, null, 10);

        assertThatCode(() -> validator.validate(List.of(node), entities)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnknownValueWithExistingApiErrorContract() {
        DialogNodeInput node = conditioned("Hours", null, "location", "Huế", WAIT_FOR_REPLY, null, 10);

        ApiException exception = assertThrows(ApiException.class, () -> validator.validate(List.of(node), entities));

        assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exception.getCode()).isEqualTo("BOT_CONFIGURATION_INVALID");
        assertThat(exception.getMessage()).contains("Hours", "Huế", "location");
        assertThat(codes(List.of(node), entities)).containsExactly("UNKNOWN_ENTITY_VALUE");
    }

    @Test
    void valueMustBelongToSpecifiedEntity() {
        List<EntityInput> schema = List.of(entity("location", "Hà Nội"), entity("product", "HCM"));
        DialogNodeInput node = conditioned("Hours", null, "location", "HCM", WAIT_FOR_REPLY, null, 10);

        assertThat(codes(List.of(node), schema)).containsExactly("UNKNOWN_ENTITY_VALUE");
    }

    @Test
    void synonymsCannotBeUsedAsCanonicalMatchValues() {
        List<EntityInput> schema = List.of(new EntityInput("location", null,
                List.of(new EntityValueInput("Hà Nội", List.of("HN")))));
        DialogNodeInput node = conditioned("Hours", null, "location", "HN", WAIT_FOR_REPLY, null, 10);

        assertThat(codes(List.of(node), schema)).containsExactly("UNKNOWN_ENTITY_VALUE");
    }

    @Test
    void doesNotRemoveAccentsFromCanonicalValues() {
        DialogNodeInput node = conditioned("Hours", null, "location", "Ha Noi", WAIT_FOR_REPLY, null, 10);

        assertThat(codes(List.of(node), entities)).containsExactly("UNKNOWN_ENTITY_VALUE");
    }

    @Test
    void rejectsMatchValueWithoutEntityIncludingBlankEntityName() {
        for (String name : Arrays.asList(null, "", "  ")) {
            DialogNodeInput node = conditioned("Hours", null, name, "HCM", WAIT_FOR_REPLY, null, 10);
            assertThat(codes(List.of(node), entities)).containsExactly("MATCH_ENTITY_REQUIRED");
        }
    }

    @Test
    void acceptsEntityPresenceConditionAndBlankValue() {
        DialogNodeInput node = conditioned("Hours", null, "location", "  ", WAIT_FOR_REPLY, null, 10);

        assertThat(validator.analyze(List.of(node), entities)).isEmpty();
    }

    @Test
    void checksReferencesWithoutThrowingNullPointerExceptions() {
        DialogNodeInput match = conditioned("Match", null, "unknown", "HCM", WAIT_FOR_REPLY, null, 10);
        DialogNodeInput required = node("Required", ROUTE, null, "hours", null, null, "unknown", WAIT_FOR_REPLY, null, 20);
        DialogNodeInput jump = route("Jump", null, JUMP_TO, "Missing", 30);
        DialogNodeInput child = route("Child", "Missing", WAIT_FOR_REPLY, null, 40);

        assertThat(codes(List.of(match, required, jump, child), entities))
                .containsExactly("UNKNOWN_MATCH_ENTITY", "UNKNOWN_REQUIRED_ENTITY", "UNKNOWN_JUMP_TARGET", "UNKNOWN_PARENT_NODE");
    }

    @Test
    void rejectsTwoNodeJumpCycleAndReportsItsPathOnce() {
        List<DialogNodeInput> nodes = List.of(route("A", null, JUMP_TO, "b", 10), route("B", null, JUMP_TO, "A", 20));

        assertThat(errors(nodes)).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.code()).isEqualTo("AUTOMATIC_CYCLE");
            assertThat(diagnostic.relatedNodes()).containsExactly("A", "B", "A");
        });
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> validator.validateOrThrow(nodes, List.of()));
    }

    @Test
    void jumpBypassesTargetIntentAndEntityConditions() {
        DialogNodeInput a = route("A", null, JUMP_TO, "B", 10);
        DialogNodeInput b = node("B", ROUTE, null, "different_intent", "location", "HCM", null, JUMP_TO, "A", 20);

        assertThat(codes(List.of(a, b), entities)).contains("AUTOMATIC_CYCLE");
    }

    @Test
    void rejectsDirectSelfJump() {
        assertThat(codes(List.of(route("A", null, JUMP_TO, "A", 10)), List.of())).containsExactly("AUTOMATIC_CYCLE");
    }

    @Test
    void acceptsDeliberateLoopSeparatedByActualWaitNode() {
        List<DialogNodeInput> nodes = List.of(
                route("Ask", null, WAIT_FOR_REPLY, null, 10),
                route("Again", "Ask", JUMP_TO, "Ask", 20));

        assertThat(errors(nodes)).isEmpty();
        assertThatCode(() -> validator.validate(nodes, List.of())).doesNotThrowAnyException();
    }

    @Test
    void nullNextStepIsWaitAndIgnoresStaleJumpTarget() {
        List<DialogNodeInput> nodes = List.of(
                route("Ask", null, null, "Again", 10), route("Again", "Ask", JUMP_TO, "Ask", 20));

        assertThat(errors(nodes)).isEmpty();
    }

    @Test
    void detectsSkipToChildThenJumpBackToParent() {
        List<DialogNodeInput> nodes = List.of(
                route("Parent", null, SKIP_USER_INPUT, null, 10), route("Child", "Parent", JUMP_TO, "Parent", 20));

        assertThat(codes(nodes, List.of())).containsExactly("AUTOMATIC_CYCLE");
    }

    @Test
    void detectsSkipToLaterSiblingThenJumpBack() {
        List<DialogNodeInput> nodes = List.of(route("A", null, SKIP_USER_INPUT, null, 10), route("B", null, JUMP_TO, "A", 20));

        assertThat(codes(nodes, List.of())).contains("AUTOMATIC_CYCLE");
    }

    @Test
    void skipDoesNotVisitEarlierSiblingsOrSiblingFolders() {
        List<DialogNodeInput> nodes = List.of(
                route("Earlier", null, WAIT_FOR_REPLY, null, 10),
                route("Last", null, SKIP_USER_INPUT, null, 20),
                node("Sibling folder", FOLDER, null, null, null, null, null, WAIT_FOR_REPLY, null, 30),
                route("Folder child", "Sibling folder", JUMP_TO, "Last", 40));

        assertThat(errors(nodes)).isEmpty();
    }

    @Test
    void childrenTakePrecedenceOverLaterSiblings() {
        List<DialogNodeInput> nodes = List.of(
                route("A", null, SKIP_USER_INPUT, null, 10),
                route("Later", null, JUMP_TO, "A", 20),
                route("Child", "A", WAIT_FOR_REPLY, null, 30));

        assertThat(errors(nodes)).isEmpty();
    }

    @Test
    void detectsNestedFolderCycleEvenWhenFoldersDeclareWait() {
        List<DialogNodeInput> nodes = List.of(
                node("Folder", FOLDER, null, null, null, null, null, WAIT_FOR_REPLY, null, 10),
                node("Nested", FOLDER, "Folder", null, null, null, null, null, null, 20),
                route("Child", "Nested", JUMP_TO, "Folder", 30));

        assertThat(codes(nodes, List.of())).containsExactly("AUTOMATIC_CYCLE");
    }

    @Test
    void acceptsFolderToWaitChildAndIgnoresFolderJumpMetadata() {
        List<DialogNodeInput> nodes = List.of(
                node("Folder", FOLDER, null, null, null, null, null, JUMP_TO, "Folder", 10),
                route("Child", "Folder", WAIT_FOR_REPLY, null, 20));

        assertThat(errors(nodes)).isEmpty();
    }

    @Test
    void detectsCycleThroughInferredRootFallback() {
        List<DialogNodeInput> nodes = List.of(
                route("A", null, SKIP_USER_INPUT, null, 10),
                node("Fallback", FALLBACK, null, null, null, null, null, JUMP_TO, "A", 999));

        assertThat(codes(nodes, List.of())).containsExactly("AUTOMATIC_CYCLE");
    }

    @Test
    void fallbackWithSkipCanSelectItself() {
        DialogNodeInput fallback = node("Fallback", FALLBACK, null, null, null, null, null, SKIP_USER_INPUT, null, 999);

        assertThat(codes(List.of(fallback), List.of())).containsExactly("AUTOMATIC_CYCLE");
    }

    @Test
    void missingRequiredEntityIsNotGuaranteedCycleBoundary() {
        List<DialogNodeInput> nodes = List.of(
                node("Ask location", ROUTE, null, "hours", null, null, "location", JUMP_TO, "Again", 10),
                route("Again", null, JUMP_TO, "Ask location", 20));

        assertThat(codes(nodes, entities)).contains("AUTOMATIC_CYCLE");
    }

    @Test
    void acceptsPathThatMustPauseBecauseRequiredEntityIsMissing() {
        List<DialogNodeInput> nodes = List.of(
                route("Start", null, SKIP_USER_INPUT, null, 10),
                conditioned("Known location", "Start", "location", null, WAIT_FOR_REPLY, null, 20),
                node("Ask location", ROUTE, "Start", "hours", null, null,
                        "location", JUMP_TO, "Start", 30));

        assertThat(errors(nodes, entities)).isEmpty();
    }

    @Test
    void jumpCanCreateCycleDespiteDifferentSourceEntityConditions() {
        List<DialogNodeInput> nodes = List.of(
                conditioned("Hanoi", null, "location", "Hà Nội", SKIP_USER_INPUT, null, 10),
                conditioned("HCM", null, "location", "HCM", JUMP_TO, "Hanoi", 20));

        assertThat(codes(nodes, entities)).containsExactly("AUTOMATIC_CYCLE");
    }

    @Test
    void mutuallyExclusiveSkipConditionsCannotFormAutomaticCycle() {
        List<DialogNodeInput> nodes = List.of(
                route("Parent", null, SKIP_USER_INPUT, null, 10),
                conditioned("Hanoi", "Parent", "location", "Hà Nội", SKIP_USER_INPUT, null, 20),
                conditioned("HCM", "Hanoi", "location", "HCM", JUMP_TO, "Parent", 30));

        assertThat(validator.analyze(nodes, entities)).isEmpty();
    }

    @Test
    void mutuallyExclusiveSkipIntentsCannotFormAutomaticCycle() {
        List<DialogNodeInput> nodes = List.of(
                route("Parent", null, SKIP_USER_INPUT, null, 10),
                node("Billing", ROUTE, "Parent", "billing", null, null, null, SKIP_USER_INPUT, null, 20),
                node("Refund", ROUTE, "Billing", "refund", null, null, null, JUMP_TO, "Parent", 30));

        assertThat(validator.analyze(nodes, List.of())).isEmpty();
    }

    @Test
    void prioritySelectionPreventsImpossibleBranchCycle() {
        List<DialogNodeInput> nodes = List.of(
                route("Parent", null, SKIP_USER_INPUT, null, 10),
                route("Safe child", "Parent", WAIT_FOR_REPLY, null, 20),
                conditioned("Later child", "Parent", "location", "HCM", JUMP_TO, "Parent", 30));

        assertThat(errors(nodes, entities)).isEmpty();
        assertThat(codes(nodes, entities)).containsExactly("SHADOWED_ROUTE");
    }

    @Test
    void rejectsPathBeyondTwentyExecutedNodes() {
        List<DialogNodeInput> nodes = jumpChain(21);

        assertThat(codes(nodes, List.of())).contains("AUTOMATIC_DEPTH_LIMIT");
        assertThat(errors(nodes)).first().satisfies(diagnostic -> assertThat(diagnostic.relatedNodes()).hasSize(21));
    }

    @Test
    void acceptsWaitAsTwentiethExecutedNode() {
        assertThat(errors(jumpChain(20))).isEmpty();
    }

    @Test
    void welcomeCannotInventRecognizedIntentOrEntityBeforeFirstUserTurn() {
        List<DialogNodeInput> nodes = jumpChain(20);
        nodes.set(0, route("Step 0", "Welcome", JUMP_TO, "Step 1", 0));
        nodes.add(node("Welcome", WELCOME, null, null, null, null, null, SKIP_USER_INPUT, null, -10));

        assertThat(errors(nodes)).isEmpty();
    }

    @Test
    void acceptsTwentyAutomaticNodesIfLastHasNoSuccessor() {
        List<DialogNodeInput> nodes = jumpChain(20);
        nodes.set(19, route("Step 19", null, SKIP_USER_INPUT, null, 190));

        assertThat(errors(nodes)).isEmpty();
    }

    @Test
    void rejectsDepthOverflowFromInferredSkipTransitions() {
        List<DialogNodeInput> nodes = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            nodes.add(route("Step " + i, i == 0 ? null : "Step " + (i - 1),
                    i == 20 ? WAIT_FOR_REPLY : SKIP_USER_INPUT, null, i * 10));
        }

        assertThat(codes(nodes, List.of())).contains("AUTOMATIC_DEPTH_LIMIT");
    }

    @Test
    void acceptsLongConversationWithWaitBoundaries() {
        List<DialogNodeInput> nodes = new ArrayList<>();
        for (int i = 0; i < 45; i++) {
            nodes.add(route("Step " + i, i == 0 ? null : "Step " + (i - 1),
                    i % 10 == 9 || i == 44 ? WAIT_FOR_REPLY : SKIP_USER_INPUT, null, i * 10));
        }

        assertThat(errors(nodes)).isEmpty();
    }

    @Test
    void rejectsParentCycleEvenWhenEveryNodeWaits() {
        List<DialogNodeInput> nodes = List.of(
                route("A", "B", WAIT_FOR_REPLY, null, 10), route("B", "C", WAIT_FOR_REPLY, null, 20),
                route("C", "a", WAIT_FOR_REPLY, null, 30));

        assertThat(codes(nodes, List.of())).containsExactly("PARENT_CYCLE");
    }

    @Test
    void rejectsSelfParent() {
        assertThat(codes(List.of(route("A", " A ", WAIT_FOR_REPLY, null, 10)), List.of())).containsExactly("PARENT_CYCLE");
    }

    @Test
    void warnsAboutShadowedSiblingWhileAllowingExplicitRouteTargetPair() {
        List<DialogNodeInput> nodes = List.of(route("Route", null, JUMP_TO, "Target", 10), route("Target", null, WAIT_FOR_REPLY, null, 20));

        assertThat(validator.analyze(nodes, List.of())).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.severity()).isEqualTo(Severity.WARNING);
            assertThat(diagnostic.code()).isEqualTo("SHADOWED_ROUTE");
            assertThat(diagnostic.nodeName()).isEqualTo("Target");
            assertThat(diagnostic.relatedNodes()).containsExactly("Route", "Target");
        });
        assertThatCode(() -> validator.validateOrThrow(nodes, List.of())).doesNotThrowAnyException();
    }

    @Test
    void warnsAboutPartialOverlapForIndependentEntityConditions() {
        List<EntityInput> schema = List.of(entity("location", "HCM"), entity("product", "eSIM"));
        List<DialogNodeInput> nodes = List.of(
                conditioned("By location", null, "location", "HCM", WAIT_FOR_REPLY, null, 10),
                conditioned("By product", null, "product", "eSIM", WAIT_FOR_REPLY, null, 20));

        assertThat(codes(nodes, schema)).containsExactly("OVERLAPPING_ROUTES");
    }

    @Test
    void warnsWhenSpecificConditionPrecedesGeneralCondition() {
        List<DialogNodeInput> nodes = List.of(
                conditioned("Specific", null, "location", "HCM", WAIT_FOR_REPLY, null, 10),
                route("General", null, WAIT_FOR_REPLY, null, 20));

        assertThat(codes(nodes, entities)).containsExactly("OVERLAPPING_ROUTES");
    }

    @Test
    void conditionsUnderDifferentParentsAreNotSiblingWarnings() {
        List<DialogNodeInput> nodes = List.of(
                route("Parent", null, SKIP_USER_INPUT, null, 10), route("Child", "Parent", WAIT_FOR_REPLY, null, 20));

        assertThat(validator.analyze(nodes, List.of())).isEmpty();
    }

    @Test
    void acceptsExistingStarterTemplateShapeWithoutWarnings() {
        List<DialogNodeInput> nodes = List.of(
                node("Welcome", WELCOME, null, null, null, null, null, WAIT_FOR_REPLY, null, 0),
                node("Greeting", ROUTE, null, "greeting", null, null, null, WAIT_FOR_REPLY, null, 10),
                node("Shop hours", ROUTE, null, "store_hours", null, null, null, SKIP_USER_INPUT, null, 20),
                starterLocation("Hà Nội hours", "Hà Nội", 30), starterLocation("HCM hours", "HCM", 40),
                starterLocation("Đà Nẵng hours", "Đà Nẵng", 50),
                node("Ask location", ROUTE, "Shop hours", "store_hours", null, null, "location", WAIT_FOR_REPLY, null, 90),
                node("Anything else", FALLBACK, null, null, null, null, null, WAIT_FOR_REPLY, null, 999));

        assertThat(validator.analyze(nodes, entities)).isEmpty();
        assertThatCode(() -> validator.validate(nodes, entities)).doesNotThrowAnyException();
    }

    @Test
    void returnsImmutableDiagnosticsAndDoesNotRetainStateBetweenCalls() {
        List<DialogNodeInput> bad = List.of(route("A", null, JUMP_TO, "A", 10));
        List<Diagnostic> diagnostics = validator.analyze(bad, List.of());

        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(diagnostics::clear);
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> diagnostics.getFirst().relatedNodes().clear());
        assertThat(validator.analyze(List.of(route("Safe", null, WAIT_FOR_REPLY, null, 10)), List.of())).isEmpty();
        assertThat(validator.analyze(bad, List.of())).isEqualTo(diagnostics);
    }

    @Test
    void rejectsNullOrMalformedNodesWithStructuredDiagnostics() {
        assertThat(codes(null, List.of())).containsExactly("CONFIGURATION_REQUIRED");
        assertThat(codes(List.of(), null)).containsExactly("CONFIGURATION_REQUIRED");
        assertThat(codes(Arrays.asList((DialogNodeInput) null), List.of())).containsExactly("INVALID_DIALOG_NODE");
        assertThat(codes(List.of(route("A", null, WAIT_FOR_REPLY, null, 10),
                route(" a ", null, WAIT_FOR_REPLY, null, 20)), List.of())).containsExactly("DUPLICATE_NODE_NAME");
    }

    @Test
    void usesLocaleIndependentCaseNormalization() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            DialogNodeInput node = conditioned("Hours", null, "CITY", "ISTANBUL", WAIT_FOR_REPLY, null, 10);
            assertThat(validator.analyze(List.of(node), List.of(entity("city", "istanbul")))).isEmpty();
        } finally {
            Locale.setDefault(original);
        }
    }

    private List<Diagnostic> errors(List<DialogNodeInput> nodes) {
        return errors(nodes, List.of());
    }

    private List<Diagnostic> errors(List<DialogNodeInput> nodes, List<EntityInput> schema) {
        return validator.analyze(nodes, schema).stream().filter(diagnostic -> diagnostic.severity() == Severity.ERROR).toList();
    }

    private List<String> codes(List<DialogNodeInput> nodes, List<EntityInput> schema) {
        return validator.analyze(nodes, schema).stream().map(Diagnostic::code).toList();
    }

    private static EntityInput entity(String name, String... values) {
        return new EntityInput(name, null, Arrays.stream(values).map(value -> new EntityValueInput(value, List.of())).toList());
    }

    private static List<DialogNodeInput> jumpChain(int size) {
        List<DialogNodeInput> nodes = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            nodes.add(route("Step " + i, null, i == size - 1 ? WAIT_FOR_REPLY : JUMP_TO,
                    i == size - 1 ? null : "Step " + (i + 1), i * 10));
        }
        return nodes;
    }

    private static DialogNodeInput starterLocation(String name, String value, int priority) {
        return node(name, ROUTE, "Shop hours", "store_hours", "location", value, null, WAIT_FOR_REPLY, null, priority);
    }

    private static DialogNodeInput conditioned(String name, String parent, String entity, String value,
                                                DialogNextStep next, String jump, int priority) {
        return node(name, ROUTE, parent, "hours", entity, value, null, next, jump, priority);
    }

    private static DialogNodeInput route(String name, String parent, DialogNextStep next, String jump, int priority) {
        return node(name, ROUTE, parent, "hours", null, null, null, next, jump, priority);
    }

    private static DialogNodeInput node(String name, DialogNodeType type, String parent, String intent, String entity,
                                        String value, String required, DialogNextStep next, String jump, int priority) {
        return new DialogNodeInput(name, type, parent, intent, entity, value, required,
                "Response", "Please provide " + required, next, jump, null, priority);
    }
}
