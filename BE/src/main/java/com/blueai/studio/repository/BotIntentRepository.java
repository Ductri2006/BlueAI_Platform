package com.blueai.studio.repository;

import com.blueai.studio.model.BotIntent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BotIntentRepository extends JpaRepository<BotIntent, String> {
    List<BotIntent> findByBot_IdOrderByNameAsc(String botId);
    void deleteByBot_Id(String botId);
}
