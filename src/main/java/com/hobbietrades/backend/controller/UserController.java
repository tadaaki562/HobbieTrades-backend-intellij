package com.hobbietrades.backend.controller;

import com.hobbietrades.backend.model.Item;
import com.hobbietrades.backend.model.Review;
import com.hobbietrades.backend.model.Trade;
import com.hobbietrades.backend.model.User;
import com.hobbietrades.backend.repository.ItemRepository;
import com.hobbietrades.backend.repository.ReviewRepository;
import com.hobbietrades.backend.repository.TradeRepository;
import com.hobbietrades.backend.repository.UserRepository;
import com.hobbietrades.backend.service.MarketValueService;
import com.hobbietrades.backend.service.MatchingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(origins = "*")
public class UserController {

    @Autowired private UserRepository userRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private TradeRepository tradeRepository;
    @Autowired private ReviewRepository reviewRepository;
    @Autowired private MatchingService matchingService;
    @Autowired private MarketValueService marketValueService;

    /**
     * GET /api/users/{id}/profile
     * Returns the exact shape profile.html expects:
     * { success, user, items, trades, reviews, subRatings }
     */
    @GetMapping("/{id}/profile")
    public ResponseEntity<Map<String, Object>> getProfile(@PathVariable Long id) {
        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "User not found"));
        }

        User user = userOpt.get();

        // ── User block ────────────────────────────────────────────────────────
        Map<String, Object> userMap = new LinkedHashMap<>();
        userMap.put("id",         user.getId());
        userMap.put("name",       user.getName());
        userMap.put("email",      user.getEmail());
        userMap.put("location",   user.getLocation());
        userMap.put("rating",     user.getRating());
        userMap.put("tradeCount", user.getTradeCount());
        userMap.put("createdAt",  user.getCreatedAt());

        // ── Items — available only ────────────────────────────────────────────
        List<Item> items = itemRepository.findByUserIdAndIsAvailableTrue(id);

        // ── Trades — ALL statuses ─────────────────────────────────────────────
        List<Trade> trades = tradeRepository.findByProposerIdOrReceiverId(id, id);

        // ── Reviews ───────────────────────────────────────────────────────────
        List<Review> reviews = reviewRepository.findByRevieweeId(id);

        // ── Sub-ratings — average each dimension across all reviews ───────────
        Map<String, Object> subRatings = new LinkedHashMap<>();
        if (!reviews.isEmpty()) {
            double itemAsDescribed = reviews.stream()
                    .filter(r -> r.getItemAsDescribed() != null)
                    .mapToInt(Review::getItemAsDescribed)
                    .average().orElse(0.0);
            double communication = reviews.stream()
                    .filter(r -> r.getCommunication() != null)
                    .mapToInt(Review::getCommunication)
                    .average().orElse(0.0);
            double meetupReliability = reviews.stream()
                    .filter(r -> r.getMeetupReliability() != null)
                    .mapToInt(Review::getMeetupReliability)
                    .average().orElse(0.0);

            subRatings.put("itemAsDescribed",   Math.round(itemAsDescribed   * 10.0) / 10.0);
            subRatings.put("communication",     Math.round(communication     * 10.0) / 10.0);
            subRatings.put("meetupReliability", Math.round(meetupReliability * 10.0) / 10.0);
        } else {
            subRatings.put("itemAsDescribed",   0.0);
            subRatings.put("communication",     0.0);
            subRatings.put("meetupReliability", 0.0);
        }

        // ── Assemble response ─────────────────────────────────────────────────
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success",    true);
        response.put("user",       userMap);
        response.put("items",      items);
        response.put("trades",     trades);
        response.put("reviews",    reviews);
        response.put("subRatings", subRatings);

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/users/{id}/for-you
     */
    @GetMapping("/{id}/for-you")
    public ResponseEntity<List<Map<String, Object>>> getForYou(
            @PathVariable Long id,
            @RequestParam(defaultValue = "20") int limit) {

        Optional<User> userOpt = userRepository.findById(id);
        if (userOpt.isEmpty()) return ResponseEntity.notFound().build();

        List<MatchingService.MatchResult> matches =
                matchingService.getMatchesForUser(userOpt.get(), limit);

        List<Map<String, Object>> result = matches.stream().map(m -> {
            Map<String, Object> breakdown = new LinkedHashMap<>();
            breakdown.put("lookingFor",    Math.round(m.lookingForScore));
            breakdown.put("category",      Math.round(m.categoryScore));
            breakdown.put("valueFairness", Math.round(m.valueFairnessScore));
            breakdown.put("condition",     Math.round(m.conditionScore));
            breakdown.put("novelty",       Math.round(m.noveltyScore));

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("item",           m.getItem());
            entry.put("matchPercent",   m.getMatchPercent());
            entry.put("matchReason",    m.getMatchReason());
            entry.put("scoreBreakdown", breakdown);
            return entry;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/users/estimate-value
     */
    @PostMapping("/estimate-value")
    public ResponseEntity<Map<String, Object>> estimateValue(
            @RequestBody Map<String, String> body) {

        String category    = body.getOrDefault("category",    "General");
        String condition   = body.getOrDefault("condition",   "Good");
        String title       = body.getOrDefault("title",       "");
        String description = body.getOrDefault("description", "");

        MarketValueService.EstimateResult result =
                marketValueService.estimateFromFields(category, condition, title, description);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("estimate",    result.estimate);
        response.put("minEstimate", result.minEstimate);
        response.put("maxEstimate", result.maxEstimate);
        response.put("range",       result.getRangeFormatted());
        response.put("confidence",  result.confidence);
        response.put("explanation", result.explanation);
        response.put("factors",     result.factors);
        return ResponseEntity.ok(response);
    }
}