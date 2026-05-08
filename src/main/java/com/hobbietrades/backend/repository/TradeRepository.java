package com.hobbietrades.backend.repository;

import com.hobbietrades.backend.model.Trade;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface TradeRepository extends JpaRepository<Trade, Long> {
    List<Trade> findByProposerId(Long proposerId);
    List<Trade> findByReceiverId(Long receiverId);
    List<Trade> findByProposerIdOrReceiverId(Long proposerId, Long receiverId);
    List<Trade> findByStatus(String status);
}