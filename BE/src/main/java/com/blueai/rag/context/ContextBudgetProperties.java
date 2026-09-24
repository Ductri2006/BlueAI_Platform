package com.blueai.rag.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Bounded context settings. Character budgets are intentional because the
 * configured LLM provider can change and may not expose a compatible tokenizer.
 */
@ConfigurationProperties(prefix = "app.rag.context")
@Validated
public class ContextBudgetProperties {

    @Min(1) @Max(100)
    private int maxRecentMessages = 20;
    @Min(1) @Max(100_000)
    private int historyCharacters = 6_500;
    @Min(1) @Max(100_000)
    private int summaryCharacters = 3_000;
    @Min(1) @Max(100_000)
    private int memoryCharacters = 2_000;
    @Min(1) @Max(100_000)
    private int linkedConversationCharacters = 5_000;
    @Min(1) @Max(100_000)
    private int relatedHistoryCharacters = 3_000;
    @Min(1) @Max(50)
    private int snapshotMessageCount = 10;
    @Min(1) @Max(10_000)
    private int snapshotMessageCharacters = 750;
    @Min(0) @Max(10)
    private int automaticHistoryLimit = 2;
    @DecimalMin("0.0") @DecimalMax("1.0")
    private double minimumRelevanceScore = 0.2;
    @Min(1) @Max(100)
    private int summaryBatchSize = 10;

    public int getMaxRecentMessages() { return maxRecentMessages; }
    public void setMaxRecentMessages(int maxRecentMessages) { this.maxRecentMessages = maxRecentMessages; }
    public int getHistoryCharacters() { return historyCharacters; }
    public void setHistoryCharacters(int historyCharacters) { this.historyCharacters = historyCharacters; }
    public int getSummaryCharacters() { return summaryCharacters; }
    public void setSummaryCharacters(int summaryCharacters) { this.summaryCharacters = summaryCharacters; }
    public int getMemoryCharacters() { return memoryCharacters; }
    public void setMemoryCharacters(int memoryCharacters) { this.memoryCharacters = memoryCharacters; }
    public int getLinkedConversationCharacters() { return linkedConversationCharacters; }
    public void setLinkedConversationCharacters(int linkedConversationCharacters) { this.linkedConversationCharacters = linkedConversationCharacters; }
    public int getRelatedHistoryCharacters() { return relatedHistoryCharacters; }
    public void setRelatedHistoryCharacters(int relatedHistoryCharacters) { this.relatedHistoryCharacters = relatedHistoryCharacters; }
    public int getSnapshotMessageCount() { return snapshotMessageCount; }
    public void setSnapshotMessageCount(int snapshotMessageCount) { this.snapshotMessageCount = snapshotMessageCount; }
    public int getSnapshotMessageCharacters() { return snapshotMessageCharacters; }
    public void setSnapshotMessageCharacters(int snapshotMessageCharacters) { this.snapshotMessageCharacters = snapshotMessageCharacters; }
    public int getAutomaticHistoryLimit() { return automaticHistoryLimit; }
    public void setAutomaticHistoryLimit(int automaticHistoryLimit) { this.automaticHistoryLimit = automaticHistoryLimit; }
    public double getMinimumRelevanceScore() { return minimumRelevanceScore; }
    public void setMinimumRelevanceScore(double minimumRelevanceScore) { this.minimumRelevanceScore = minimumRelevanceScore; }
    public int getSummaryBatchSize() { return summaryBatchSize; }
    public void setSummaryBatchSize(int summaryBatchSize) { this.summaryBatchSize = summaryBatchSize; }
}
