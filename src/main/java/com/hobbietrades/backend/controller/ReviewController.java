package com.hobbietrades.backend.controller;

import com.hobbietrades.backend.model.Review;
import com.hobbietrades.backend.model.Trade;
import com.hobbietrades.backend.model.User;
import com.hobbietrades.backend.repository.ReviewRepository;
import com.hobbietrades.backend.repository.TradeRepository;
import com.hobbietrades.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/reviews")
public class ReviewController {

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TradeRepository tradeRepository;

    // POST /api/reviews — submit a review
    @PostMapping
    public ResponseEntity<Map<String, Object>> submitReview(
            @RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();

        try {
            Long reviewerId = Long.parseLong(body.get("reviewerId").toString());
            Long revieweeId = Long.parseLong(body.get("revieweeId").toString());
            Long tradeId = Long.parseLong(body.get("tradeId").toString());
            Integer overall = Integer.parseInt(body.get("overallRating").toString());

            // Prevent duplicate review for the same trade by the same user
            Optional<Review> existing =
                    reviewRepository.findByReviewerIdAndTradeId(reviewerId, tradeId);
            if (existing.isPresent()) {
                response.put("success", false);
                response.put("message", "You already submitted a review for this trade.");
                return ResponseEntity.badRequest().body(response);
            }

            Optional<User> reviewer = userRepository.findById(reviewerId);
            Optional<User> reviewee = userRepository.findById(revieweeId);
            Optional<Trade> trade = tradeRepository.findById(tradeId);

            if (reviewer.isEmpty() || reviewee.isEmpty() || trade.isEmpty()) {
                response.put("success", false);
                response.put("message", "Invalid reviewer, reviewee, or trade ID.");
                return ResponseEntity.badRequest().body(response);
            }

            Review review = new Review();
            review.setReviewer(reviewer.get());
            review.setReviewee(reviewee.get());
            review.setTrade(trade.get());
            review.setOverallRating(overall);

            if (body.get("itemAsDescribed") != null)
                review.setItemAsDescribed(Integer.parseInt(body.get("itemAsDescribed").toString()));
            if (body.get("communication") != null)
                review.setCommunication(Integer.parseInt(body.get("communication").toString()));
            if (body.get("meetupReliability") != null)
                review.setMeetupReliability(Integer.parseInt(body.get("meetupReliability").toString()));
            if (body.get("reviewText") != null)
                review.setReviewText(body.get("reviewText").toString());
            if (body.get("tags") != null)
                review.setTags(body.get("tags").toString());

            reviewRepository.save(review);

            // Recalculate and update the reviewee's average rating
            List<Review> allReviews = reviewRepository.findByRevieweeId(revieweeId);
            double avg = allReviews.stream()
                    .mapToInt(Review::getOverallRating)
                    .average()
                    .orElse(0.0);
            // Round to 1 decimal place
            double rounded = Math.round(avg * 10.0) / 10.0;
            User revieweeUser = reviewee.get();
            revieweeUser.setRating(rounded);
            userRepository.save(revieweeUser);

            response.put("success", true);
            response.put("message", "Review submitted! Thank you.");
            response.put("reviewId", review.getId());
            response.put("newRating", rounded);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Error submitting review: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }

    // GET /api/reviews/user/{userId} — get all reviews FOR a user (their received reviews)
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<Review>> getReviewsForUser(@PathVariable Long userId) {
        return ResponseEntity.ok(reviewRepository.findByRevieweeId(userId));
    }

    // GET /api/reviews/by/{userId} — get all reviews written BY a user
    @GetMapping("/by/{userId}")
    public ResponseEntity<List<Review>> getReviewsByUser(@PathVariable Long userId) {
        return ResponseEntity.ok(reviewRepository.findByReviewerId(userId));
    }
}