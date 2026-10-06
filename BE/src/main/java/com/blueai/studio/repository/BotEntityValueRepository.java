package com.blueai.studio.repository;

import com.blueai.studio.model.BotEntityValue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BotEntityValueRepository extends JpaRepository<BotEntityValue, String> {
    List<BotEntityValue> findByEntity_Bot_IdOrderByEntity_NameAscCanonicalValueAsc(String botId);
    void deleteByEntity_Bot_Id(String botId);
}
