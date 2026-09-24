package com.blueai.rag.context;

import com.blueai.rag.conversation.model.Conversation;
import com.blueai.rag.conversation.model.ConversationContextMode;
import com.blueai.rag.conversation.model.ConversationLink;
import com.blueai.rag.conversation.model.ConversationMessage;
import com.blueai.rag.conversation.model.MessageRole;
import com.blueai.rag.conversation.repository.ConversationLinkRepository;
import com.blueai.rag.conversation.repository.ConversationMessageRepository;
import com.blueai.rag.conversation.repository.ConversationRepository;
import com.blueai.rag.conversation.repository.ConversationSummaryRepository;
import com.blueai.rag.conversation.service.ConversationContextSnapshotFactory;
import com.blueai.rag.memory.model.MemoryScope;
import com.blueai.rag.memory.model.MemoryStatus;
import com.blueai.rag.memory.model.UserMemory;
import com.blueai.rag.memory.repository.UserMemoryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves only context that the authenticated owner is allowed to use. The
 * returned records are bounded and carry no authority: they remain untrusted
 * factual references when inserted into the LLM prompt.
 */
@Service
public class ConversationContextService {

    private final ConversationRepository conversationRepository;
    private final ConversationMessageRepository messageRepository;
    private final ConversationSummaryRepository summaryRepository;
    private final ConversationLinkRepository linkRepository;
    private final UserMemoryRepository memoryRepository;
    private final ConversationContextSnapshotFactory snapshotFactory;
    private final ContextBudgetPolicy budgetPolicy;
    private final ContextRelevanceRanker relevanceRanker;

    public ConversationContextService(
            ConversationRepository conversationRepository,
            ConversationMessageRepository messageRepository,
            ConversationSummaryRepository summaryRepository,
            ConversationLinkRepository linkRepository,
            UserMemoryRepository memoryRepository,
            ConversationContextSnapshotFactory snapshotFactory,
            ContextBudgetPolicy budgetPolicy,
            ContextRelevanceRanker relevanceRanker
    ) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.summaryRepository = summaryRepository;
        this.linkRepository = linkRepository;
        this.memoryRepository = memoryRepository;
        this.snapshotFactory = snapshotFactory;
        this.budgetPolicy = budgetPolicy;
        this.relevanceRanker = relevanceRanker;
    }

    @Transactional(readOnly = true)
    public ResolvedConversationContext resolve(String ownerId, Conversation conversation, String question) {
        List<ConversationHistoryMessage> history = loadCurrentHistory(conversation.getId());
        String summary = summaryRepository.findByConversation_Id(conversation.getId())
                .map(value -> budgetPolicy.limit(value.getContent(), budgetPolicy.getSummaryCharacters()))
                .orElse("");
        List<UserMemory> memories = selectMemories(ownerId, conversation, question);

        ConversationContextMode mode = conversation.getContextMode() == null
                ? ConversationContextMode.CURRENT_CONVERSATION
                : conversation.getContextMode();
        List<ConversationContextFragment> linked = mode == ConversationContextMode.CURRENT_CONVERSATION
                ? List.of()
                : loadLinkedContexts(conversation.getId());
        List<ConversationContextFragment> related = mode == ConversationContextMode.RELEVANT_HISTORY
                ? loadRelevantHistory(ownerId, conversation, question, linked)
                : List.of();

        List<ConversationContextFragment> allContexts = new ArrayList<>(linked.size() + related.size());
        allContexts.addAll(linked);
        allContexts.addAll(related);
        // Missing personal facts must yield "not recorded", not unrelated KB evidence.
        boolean contextRecall = relevanceRanker.isContextRecallQuestion(question);
        List<ConversationContextSource> sources = buildSources(allContexts, memories);
        if (!history.isEmpty() || !summary.isBlank()) {
            sources.addFirst(new ConversationContextSource(
                    ContextSourceType.CURRENT_CONVERSATION,
                    conversation.getId(),
                    conversation.getTitle(),
                    null
            ));
        }
        return new ResolvedConversationContext(
                contextualizeRetrievalQuery(question, history, summary, allContexts),
                history,
                summary,
                memories,
                allContexts,
                sources,
                contextRecall
        );
    }

    private List<ConversationHistoryMessage> loadCurrentHistory(String conversationId) {
        List<ConversationMessage> chronological = new ArrayList<>(messageRepository
                .findByConversation_IdOrderByCreatedAtDescIdDesc(
                        conversationId,
                        PageRequest.of(0, budgetPolicy.getMaxRecentMessages())
                ));
        Collections.reverse(chronological);
        return budgetPolicy.keepRecentHistory(chronological);
    }

    private List<UserMemory> selectMemories(String ownerId, Conversation conversation, String question) {
        List<RankedMemory> ranked = memoryRepository
                .findByOwner_IdAndStatusOrderByUpdatedAtDesc(ownerId, MemoryStatus.CONFIRMED)
                .stream()
                .filter(memory -> memory.getExpiresAt() == null || memory.getExpiresAt().isAfter(java.time.Instant.now()))
                .filter(memory -> memory.getScope() == MemoryScope.USER
                        || (memory.getConversation() != null && memory.getConversation().getId().equals(conversation.getId())))
                .map(memory -> new RankedMemory(
                        memory,
                        memory.getScope() == MemoryScope.CONVERSATION,
                        relevanceRanker.score(question, memory.getMemoryKey() + " " + memory.getContent())
                ))
                .filter(item -> item.conversationScoped() || item.score() > 0 || relevanceRanker.isPreferenceKey(item.memory().getMemoryKey()))
                .sorted(Comparator.comparing(RankedMemory::conversationScoped).reversed()
                        .thenComparing(Comparator.comparingDouble(RankedMemory::score).reversed())
                        .thenComparing(item -> item.memory().getUpdatedAt(), Comparator.reverseOrder()))
                .toList();
        return budgetPolicy.keepMemories(ranked.stream().map(RankedMemory::memory).toList());
    }

    private List<ConversationContextFragment> loadLinkedContexts(String targetConversationId) {
        List<ConversationContextFragment> fragments = linkRepository
                .findByTargetConversation_IdOrderByCreatedAtDesc(targetConversationId)
                .stream()
                .map(link -> new ConversationContextFragment(
                        ContextSourceType.LINKED_CONVERSATION,
                        link.getSourceConversation().getId(),
                        link.getSourceConversation().getTitle(),
                        link.getSourceSnapshotContent(),
                        1
                ))
                .toList();
        return budgetPolicy.keepFragments(fragments, budgetPolicy.getLinkedConversationCharacters());
    }

    private List<ConversationContextFragment> loadRelevantHistory(
            String ownerId,
            Conversation currentConversation,
            String question,
            List<ConversationContextFragment> linked
    ) {
        Set<String> alreadyIncluded = new HashSet<>();
        alreadyIncluded.add(currentConversation.getId());
        linked.forEach(fragment -> alreadyIncluded.add(fragment.conversationId()));

        List<RankedFragment> candidates = new ArrayList<>();
        for (Conversation candidate : conversationRepository.findTop50ByOwner_IdOrderByUpdatedAtDesc(ownerId)) {
            if (alreadyIncluded.contains(candidate.getId())) continue;
            ConversationContextSnapshotFactory.Snapshot snapshot = snapshotFactory.capture(candidate);
            double score = relevanceRanker.score(question, candidate.getTitle() + "\n" + snapshot.content());
            if (score < budgetPolicy.getMinimumRelevanceScore()) continue;
            candidates.add(new RankedFragment(new ConversationContextFragment(
                    ContextSourceType.RELATED_HISTORY,
                    candidate.getId(),
                    candidate.getTitle(),
                    snapshot.content(),
                    score
            )));
        }
        List<ConversationContextFragment> best = candidates.stream()
                .map(RankedFragment::fragment)
                .sorted(Comparator.comparing(ConversationContextFragment::relevanceScore).reversed())
                .limit(budgetPolicy.getAutomaticHistoryLimit())
                .toList();
        return budgetPolicy.keepFragments(best, budgetPolicy.getRelatedHistoryCharacters());
    }

    private List<ConversationContextSource> buildSources(
            List<ConversationContextFragment> contexts,
            List<UserMemory> memories
    ) {
        List<ConversationContextSource> sources = new ArrayList<>();
        contexts.forEach(fragment -> sources.add(new ConversationContextSource(
                fragment.type(), fragment.conversationId(), fragment.title(), null
        )));
        memories.forEach(memory -> sources.add(new ConversationContextSource(
                ContextSourceType.USER_MEMORY,
                memory.getConversation() == null ? null : memory.getConversation().getId(),
                memory.getConversation() == null ? "Ghi nhớ cá nhân" : "Ghi nhớ của hội thoại",
                memory.getMemoryKey()
        )));
        return sources;
    }

    private String contextualizeRetrievalQuery(
            String question,
            List<ConversationHistoryMessage> history,
            String summary,
            List<ConversationContextFragment> contexts
    ) {
        if (!relevanceRanker.isFollowUpQuestion(question)) return question;
        String anchor = history.stream()
                .filter(message -> message.role() == MessageRole.USER)
                .map(ConversationHistoryMessage::content)
                .reduce((previous, current) -> current)
                .orElse("");
        if (anchor.isBlank()) anchor = summary;
        if (anchor.isBlank() && !contexts.isEmpty()) anchor = contexts.getFirst().content();
        if (anchor.isBlank()) return question;
        return question + "\nChủ đề hội thoại gần đây: " + budgetPolicy.limit(anchor, 500);
    }

    private record RankedMemory(UserMemory memory, boolean conversationScoped, double score) {
    }

    private record RankedFragment(ConversationContextFragment fragment) {
    }
}
