package com.blueai.studio.repository;

import com.blueai.studio.model.BotEntityDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BotEntityDefinitionRepository extends JpaRepository<BotEntityDefinition, String> {
    List<BotEntityDefinition> findByBot_IdOrderByNameAsc(String botId);
    void deleteByBot_Id(String botId);
}
