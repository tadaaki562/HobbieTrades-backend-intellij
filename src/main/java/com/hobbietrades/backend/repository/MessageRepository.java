package com.hobbietrades.backend.repository;

import com.hobbietrades.backend.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    // Get all messages for a specific trade, ordered by time
    List<Message> findByTradeIdOrderBySentAtAsc(Long tradeId);
}