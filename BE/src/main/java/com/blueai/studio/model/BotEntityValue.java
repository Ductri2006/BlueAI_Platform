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
        name = "studio_bot_entity_values",
        indexes = @Index(name = "idx_studio_entity_value_entity", columnList = "entity_id"),
        uniqueConstraints = @UniqueConstraint(name = "uk_studio_entity_value", columnNames = {"entity_id", "canonical_value"})
)
public class BotEntityValue {

    @Id
    @Column(length = 36, nullable = false, updatable = false, columnDefinition = "char(36)")
    private String id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "entity_id", nullable = false, foreignKey = @ForeignKey(name = "fk_studio_entity_value_entity"))
    private BotEntityDefinition entity;

    @Column(name = "canonical_value", length = 150, nullable = false)
    private String canonicalValue;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "studio_bot_entity_synonyms",
            joinColumns = @JoinColumn(name = "entity_value_id"),
            foreignKey = @ForeignKey(name = "fk_studio_synonym_value")
    )
    @Column(name = "synonym", length = 150, nullable = false)
    private List<String> synonyms = new ArrayList<>();

    protected BotEntityValue() {
    }

    public BotEntityValue(BotEntityDefinition entity, String canonicalValue, List<String> synonyms) {
        this.id = UUID.randomUUID().toString();
        this.entity = entity;
        this.canonicalValue = canonicalValue;
        this.synonyms = new ArrayList<>(synonyms);
    }

    public String getId() { return id; }
    public BotEntityDefinition getEntity() { return entity; }
    public String getCanonicalValue() { return canonicalValue; }
    public List<String> getSynonyms() { return List.copyOf(synonyms); }
}
