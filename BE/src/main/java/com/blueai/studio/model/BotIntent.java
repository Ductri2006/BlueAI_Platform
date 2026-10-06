package com.blueai.studio.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "studio_bot_intents",
        indexes = @Index(name = "idx_studio_intent_bot", columnList = "bot_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_studio_intent_bot_name", columnNames = {"bot_id", "name"})
)
public class BotIntent {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "bot_id", nullable = false, foreignKey = @ForeignKey(name = "fk_studio_intent_bot"))
    private Bot bot;

    @Column(length = 100, nullable = false)
    private String name;

    @Column(length = 500)
    private String description;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "studio_bot_intent_examples",
            joinColumns = @JoinColumn(name = "intent_id"),
            foreignKey = @ForeignKey(name = "fk_studio_intent_example_intent")
    )
    @Column(name = "phrase", length = 500, nullable = false)
    private List<String> examples = new ArrayList<>();

    protected BotIntent() {
    }

    public BotIntent(Bot bot, String name, String description, List<String> examples) {
        this.id = UUID.randomUUID().toString();
        this.bot = bot;
        this.name = name;
        this.description = description;
        this.examples = new ArrayList<>(examples);
    }

    public String getId() { return id; }
    public Bot getBot() { return bot; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public List<String> getExamples() { return List.copyOf(examples); }
}
