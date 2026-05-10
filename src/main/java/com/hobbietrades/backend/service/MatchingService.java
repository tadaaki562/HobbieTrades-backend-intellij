package com.hobbietrades.backend.service;

import com.hobbietrades.backend.model.Item;
import com.hobbietrades.backend.model.Trade;
import com.hobbietrades.backend.model.User;
import com.hobbietrades.backend.repository.ItemRepository;
import com.hobbietrades.backend.repository.TradeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MatchingService — powers the "For You" page.
 *
 * Scoring breakdown (100 points total):
 *  35 pts — lookingFor / wantsCategory keyword match
 *  25 pts — category preference (derived from trade history)
 *  20 pts — value fairness (within ±40% of user's avg item value)
 *  10 pts — condition tier match
 *  10 pts — novelty bonus (user hasn't seen/proposed this category before)
 */
@Service
public class MatchingService {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private TradeRepository tradeRepository;

    // FIX: was "Mint"→4 and "Poor"→1 which never matched.
    // Must match exact conditionLabel values stored in DB: Like New, Good, Fair, Worn
    private static final Map<String, Integer> CONDITION_TIER = Map.of(
            "Like New", 4,
            "Good",     3,
            "Fair",     2,
            "Worn",     1
    );

    private static final BigDecimal ZERO             = BigDecimal.ZERO;
    private static final BigDecimal LOWER_RATIO      = new BigDecimal("0.6");
    private static final BigDecimal UPPER_RATIO      = new BigDecimal("1.67");
    private static final BigDecimal ONE              = BigDecimal.ONE;
    private static final BigDecimal DEVIATION_DIVISOR = new BigDecimal("0.67");
    private static final BigDecimal SCORE_20         = new BigDecimal("20");

    /**
     * Returns a ranked list of MatchResult for the given user,
     * each containing the candidate Item and its score breakdown.
     */
    public List<MatchResult> getMatchesForUser(User user, int limit) {
        List<Item> candidates = itemRepository.findByIsAvailableTrue()
                .stream()
                .filter(item -> item.getUser() == null || !item.getUser().getId().equals(user.getId()))
                .collect(Collectors.toList());

        if (candidates.isEmpty()) return Collections.emptyList();

        UserProfile profile = buildUserProfile(user);

        return candidates.stream()
                .map(item -> scoreItem(item, user, profile))
                .sorted(Comparator.comparingDouble(MatchResult::getTotalScore).reversed())
                .limit(limit)
                .collect(Collectors.toList());
    }

    private UserProfile buildUserProfile(User user) {
        UserProfile profile = new UserProfile();

        List<Item> myItems = itemRepository.findByUserId(user.getId());
        profile.myItems = myItems;

        OptionalDouble avgOpt = myItems.stream()
                .filter(i -> i.getEstimatedValue() != null
                        && i.getEstimatedValue().compareTo(ZERO) > 0)
                .mapToDouble(i -> i.getEstimatedValue().doubleValue())
                .average();
        profile.avgItemValue = avgOpt.orElse(1500.0);

        for (Item item : myItems) {
            if (item.getCategory() != null) {
                profile.myCategories.add(item.getCategory());
            }
            if (item.getLookingFor() != null && !item.getLookingFor().isBlank()) {
                String[] tokens = item.getLookingFor().toLowerCase().split("[,;\\s]+");
                Collections.addAll(profile.lookingForKeywords, tokens);
            }
        }

        // FIX: was "Mint" — now correctly maps to "Like New" tier via CONDITION_TIER
        profile.preferredConditionTier = myItems.stream()
                .filter(i -> i.getConditionLabel() != null)
                .map(i -> CONDITION_TIER.getOrDefault(i.getConditionLabel(), 3))
                .mapToInt(Integer::intValue)
                .average()
                .orElse(3.0);

        List<Trade> trades = tradeRepository.findByProposerIdOrReceiverId(user.getId(), user.getId());
        for (Trade trade : trades) {
            if (trade.getOfferedItem() != null && trade.getOfferedItem().getCategory() != null) {
                profile.tradedCategories.merge(trade.getOfferedItem().getCategory(), 1, Integer::sum);
            }
            if (trade.getRequestedItem() != null && trade.getRequestedItem().getCategory() != null) {
                profile.tradedCategories.merge(trade.getRequestedItem().getCategory(), 1, Integer::sum);
            }
        }

        return profile;
    }

    private MatchResult scoreItem(Item item, User user, UserProfile profile) {
        MatchResult result = new MatchResult(item);

        // ── Score 1: lookingFor match (35 pts) ───────────────────────────────
        double lookingForScore = 0.0;
        if (!profile.lookingForKeywords.isEmpty() && item.getTitle() != null) {
            String title    = item.getTitle().toLowerCase();
            String category = item.getCategory() != null ? item.getCategory().toLowerCase() : "";
            String desc     = item.getDescription() != null ? item.getDescription().toLowerCase() : "";

            long hits = profile.lookingForKeywords.stream()
                    .filter(kw -> kw.length() >= 3)
                    .filter(kw -> title.contains(kw) || category.contains(kw) || desc.contains(kw))
                    .count();

            lookingForScore = Math.min(35.0, hits * 12.0);

            if (item.getCategory() != null) {
                String catLower = item.getCategory().toLowerCase();
                boolean exactCategoryHit = profile.lookingForKeywords.stream()
                        .anyMatch(kw -> catLower.contains(kw) || kw.contains(catLower));
                if (exactCategoryHit) lookingForScore = 35.0;
            }
        }
        result.lookingForScore = lookingForScore;

        // ── Score 2: category preference (25 pts) ────────────────────────────
        double categoryScore = 0.0;
        if (item.getCategory() != null) {
            if (profile.myCategories.contains(item.getCategory())) {
                categoryScore += 15.0;
            }
            int tradeCount = profile.tradedCategories.getOrDefault(item.getCategory(), 0);
            categoryScore += Math.min(10.0, tradeCount * 3.0);
        }
        result.categoryScore = categoryScore;

        // ── Score 3: value fairness (20 pts) ─────────────────────────────────
        double valueFairnessScore = 0.0;
        if (item.getEstimatedValue() != null
                && item.getEstimatedValue().compareTo(ZERO) > 0
                && profile.avgItemValue > 0) {

            BigDecimal avgBD = BigDecimal.valueOf(profile.avgItemValue);
            BigDecimal ratio = item.getEstimatedValue()
                    .divide(avgBD, 6, RoundingMode.HALF_UP);

            if (ratio.compareTo(LOWER_RATIO) >= 0 && ratio.compareTo(UPPER_RATIO) <= 0) {
                BigDecimal deviation = ONE.subtract(ratio).abs();
                BigDecimal fairness  = SCORE_20.multiply(
                        BigDecimal.ONE.subtract(
                                deviation.divide(DEVIATION_DIVISOR, 6, RoundingMode.HALF_UP)
                        )
                );
                valueFairnessScore = Math.max(0.0, fairness.doubleValue());
            }
        } else {
            valueFairnessScore = 10.0;
        }
        result.valueFairnessScore = valueFairnessScore;

        // ── Score 4: condition match (10 pts) ────────────────────────────────
        // FIX: CONDITION_TIER now has correct keys (Like New/Good/Fair/Worn)
        // so getOrDefault will actually find matches instead of always returning 3
        double conditionScore = 0.0;
        if (item.getConditionLabel() != null) {
            int itemTier = CONDITION_TIER.getOrDefault(item.getConditionLabel(), 3);
            double tierDiff = Math.abs(itemTier - profile.preferredConditionTier);
            conditionScore = 10.0 * Math.max(0, 1.0 - (tierDiff / 3.0));
        } else {
            conditionScore = 5.0;
        }
        result.conditionScore = conditionScore;

        // ── Score 5: novelty bonus (10 pts) ──────────────────────────────────
        double noveltyScore = 0.0;
        if (item.getCategory() != null) {
            boolean isNovel = !profile.myCategories.contains(item.getCategory())
                    && !profile.tradedCategories.containsKey(item.getCategory());
            noveltyScore = isNovel ? 10.0 : 0.0;
        }
        result.noveltyScore = noveltyScore;

        result.totalScore = lookingForScore + categoryScore + valueFairnessScore
                + conditionScore + noveltyScore;

        return result;
    }

    // ── Inner classes ─────────────────────────────────────────────────────────

    public static class MatchResult {
        private final Item item;
        public double lookingForScore;
        public double categoryScore;
        public double valueFairnessScore;
        public double conditionScore;
        public double noveltyScore;
        public double totalScore;

        public MatchResult(Item item) { this.item = item; }
        public Item getItem()         { return item; }
        public double getTotalScore() { return totalScore; }

        /** Returns score as 0–100 percentage */
        public int getMatchPercent() {
            return (int) Math.min(100, Math.round(totalScore));
        }

        /** Human-readable reason why this item was recommended */
        public String getMatchReason() {
            if (lookingForScore >= 30)    return "Matches what you're looking for";
            if (categoryScore   >= 20)    return "Category you love";
            if (valueFairnessScore >= 18) return "Great value match";
            if (noveltyScore    >= 10)    return "Discover something new";
            return "Good overall match";
        }
    }

    private static class UserProfile {
        List<Item>           myItems            = new ArrayList<>();
        Set<String>          myCategories       = new HashSet<>();
        Set<String>          lookingForKeywords = new HashSet<>();
        Map<String, Integer> tradedCategories   = new HashMap<>();
        double               avgItemValue       = 1500.0;
        double               preferredConditionTier = 3.0;
    }
}