package com.blueai.studio.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.UUID;

@Entity
@Table(
        name = "studio_bot_entities",
        indexes = @Index(name = "idx_studio_entity_bot", columnList = "bot_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_studio_entity_bot_name", columnNames = {"bot_id", "name"})
)
public class BotEntityDefinition {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "bot_id", nullable = false, foreignKey = @ForeignKey(name = "fk_studio_entity_bot"))
    private Bot bot;

    @Column(length = 100, nullable = false)
    private String name;

    @Column(length = 500)
    private String description;

    protected BotEntityDefinition() {
    }

    public BotEntityDefinition(Bot bot, String name, String description) {
        this.id = UUID.randomUUID().toString();
        this.bot = bot;
        this.name = name;
        this.description = description;
    }

    public String getId() { return id; }
    public Bot getBot() { return bot; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}
