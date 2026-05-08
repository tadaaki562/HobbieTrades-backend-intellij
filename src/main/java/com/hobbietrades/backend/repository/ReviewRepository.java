package com.hobbietrades.backend.repository;

import com.hobbietrades.backend.model.Review;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {

    // All reviews written ABOUT a user (for their profile rating)
    List<Review> findByRevieweeId(Long revieweeId);

    // All reviews written BY a user
    List<Review> findByReviewerId(Long reviewerId);

    // Check if a reviewer already submitted a review for a specific trade
    // (prevents duplicate reviews per trade per user)
    Optional<Review> findByReviewerIdAndTradeId(Long reviewerId, Long tradeId);
}