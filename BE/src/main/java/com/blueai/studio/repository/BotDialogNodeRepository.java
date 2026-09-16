package com.blueai.studio.repository;

import com.blueai.studio.model.BotDialogNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BotDialogNodeRepository extends JpaRepository<BotDialogNode, String> {
    List<BotDialogNode> findByBot_IdOrderByPriorityAsc(String botId);
    void deleteByBot_Id(String botId);
}
