package com.blueai.studio.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "studio_bot_dialog_nodes", indexes = @Index(name = "idx_studio_dialog_bot_priority", columnList = "bot_id,priority"))
public class BotDialogNode {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "bot_id", nullable = false, foreignKey = @ForeignKey(name = "fk_studio_dialog_bot"))
    private Bot bot;

    @Column(name = "node_name", length = 120, nullable = false)
    private String nodeName;

    @Column(name = "parent_node", length = 120)
    private String parentNode;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", length = 20, nullable = false)
    private DialogNodeType nodeType;

    @Column(name = "match_intent", length = 100)
    private String matchIntent;

    @Column(name = "match_entity_name", length = 100)
    private String matchEntityName;

    @Column(name = "match_entity_value", length = 150)
    private String matchEntityValue;

    @Column(name = "required_entity_name", length = 100)
    private String requiredEntityName;

    @Lob
    @Column(name = "response_text", nullable = false, columnDefinition = "longtext")
    private String responseText;

    @Column(name = "missing_entity_prompt", length = 1000)
    private String missingEntityPrompt;

    @Enumerated(EnumType.STRING)
    @Column(name = "next_step", length = 30)
    private DialogNextStep nextStep;

    @Column(name = "jump_to_node", length = 120)
    private String jumpToNode;

    @Column(name = "irrelevant_response_text", length = 1000)
    private String irrelevantResponseText;

    @Lob
    @Column(name = "english_response_text", columnDefinition = "longtext")
    private String englishResponseText;

    @Column(name = "english_missing_entity_prompt", length = 1000)
    private String englishMissingEntityPrompt;

    @Column(name = "english_irrelevant_response_text", length = 1000)
    private String englishIrrelevantResponseText;

    @Lob
    @Column(name = "reply_translations_json", columnDefinition = "longtext")
    private String replyTranslationsJson;

    @Column(nullable = false)
    private int priority;

    @Enumerated(EnumType.STRING)
    @Column(name = "context_reuse_policy", length = 30, nullable = false)
    private DialogContextReusePolicy contextReusePolicy = DialogContextReusePolicy.NONE;

    protected BotDialogNode() {
    }

    public BotDialogNode(
            Bot bot,
            String nodeName,
            DialogNodeType nodeType,
            String parentNode,
            String matchIntent,
            String matchEntityName,
            String matchEntityValue,
            String requiredEntityName,
            String responseText,
            String missingEntityPrompt,
            DialogNextStep nextStep,
            String jumpToNode,
            String irrelevantResponseText,
            int priority
    ) {
        this.id = UUID.randomUUID().toString();
        this.bot = bot;
        this.nodeName = nodeName;
        this.nodeType = nodeType;
        this.parentNode = parentNode;
        this.matchIntent = matchIntent;
        this.matchEntityName = matchEntityName;
        this.matchEntityValue = matchEntityValue;
        this.requiredEntityName = requiredEntityName;
        this.responseText = responseText;
        this.missingEntityPrompt = missingEntityPrompt;
        this.nextStep = nextStep;
        this.jumpToNode = jumpToNode;
        this.irrelevantResponseText = irrelevantResponseText;
        this.priority = priority;
    }

    public String getId() { return id; }
    public Bot getBot() { return bot; }
    public String getNodeName() { return nodeName; }
    public DialogNodeType getNodeType() { return nodeType; }
    public String getParentNode() { return parentNode; }
    public String getMatchIntent() { return matchIntent; }
    public String getMatchEntityName() { return matchEntityName; }
    public String getMatchEntityValue() { return matchEntityValue; }
    public String getRequiredEntityName() { return requiredEntityName; }
    public String getResponseText() { return responseText; }
    public String getMissingEntityPrompt() { return missingEntityPrompt; }
    public DialogNextStep getNextStep() { return nextStep; }
    public String getJumpToNode() { return jumpToNode; }
    public String getIrrelevantResponseText() { return irrelevantResponseText; }
    public int getPriority() { return priority; }
    public String getEnglishResponseText() { return englishResponseText; }
    public String getEnglishMissingEntityPrompt() { return englishMissingEntityPrompt; }
    public String getEnglishIrrelevantResponseText() { return englishIrrelevantResponseText; }
    public String getReplyTranslationsJson() { return replyTranslationsJson; }
    public void setReplyTranslationsJson(String value) { replyTranslationsJson = value; }
    public void setEnglishReplies(String response, String missing, String irrelevant) {
        englishResponseText = response;
        englishMissingEntityPrompt = missing;
        englishIrrelevantResponseText = irrelevant;
    }
    public DialogContextReusePolicy getContextReusePolicy() { return contextReusePolicy; }
    public void setContextReusePolicy(DialogContextReusePolicy policy) {
        contextReusePolicy = policy == null ? DialogContextReusePolicy.NONE : policy;
    }
}
