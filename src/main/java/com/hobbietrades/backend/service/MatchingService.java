package com.hobbietrades.backend.service;

import com.hobbietrades.backend.model.Item;
import com.hobbietrades.backend.repository.ItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class MatchingService {

    @Autowired
    private ItemRepository itemRepository;

    public List<Map<String, Object>> findMatches(Long itemId, Long userId) {
        // Get the source item
        Optional<Item> sourceOpt = itemRepository.findById(itemId);
        if (sourceOpt.isEmpty()) return new ArrayList<>();

        Item source = sourceOpt.get();

        // Safety check
        if (source.getEstimatedValue() == null) return new ArrayList<>();

        double sourceValue = source.getEstimatedValue().doubleValue();
        Set<String> sourceTags = extractTags(
                (source.getLookingFor() != null ? source.getLookingFor() : "") + " " +
                        (source.getCategory() != null ? source.getCategory() : "")
        );

        // Get all available items
        List<Item> allItems = itemRepository.findByIsAvailableTrue();
        List<Item> candidates = new ArrayList<>();

        for (Item item : allItems) {
            // Skip if same item
            if (item.getId().equals(itemId)) continue;
            // Skip if user is null
            if (item.getUser() == null) continue;
            // Skip if same user
            if (item.getUser().getId().equals(userId)) continue;
            // Skip if no value
            if (item.getEstimatedValue() == null) continue;
            candidates.add(item);
        }

        if (candidates.isEmpty()) return new ArrayList<>();

        // Score each candidate
        List<Map<String, Object>> scored = new ArrayList<>();

        for (Item candidate : candidates) {
            double candidateValue = candidate.getEstimatedValue().doubleValue();

            // Fairness score
            double maxVal = Math.max(sourceValue, candidateValue);
            double fairnessScore = maxVal > 0 ?
                    1.0 - (Math.abs(sourceValue - candidateValue) / maxVal) : 0;

            // Preference score — Jaccard similarity
            Set<String> candidateTags = extractTags(
                    (candidate.getTitle() != null ? candidate.getTitle() : "") + " " +
                            (candidate.getCategory() != null ? candidate.getCategory() : "") + " " +
                            (candidate.getLookingFor() != null ? candidate.getLookingFor() : "")
            );
            double preferenceScore = jaccardSimilarity(sourceTags, candidateTags);

            // Combined match score
            double matchScore = (0.5 * fairnessScore) + (0.5 * preferenceScore);

            // Build result map
            Map<String, Object> itemMap = new HashMap<>();
            itemMap.put("id", candidate.getId());
            itemMap.put("title", candidate.getTitle());
            itemMap.put("category", candidate.getCategory());
            itemMap.put("conditionLabel", candidate.getConditionLabel());
            itemMap.put("estimatedValue", candidate.getEstimatedValue());
            itemMap.put("location", candidate.getLocation());
            itemMap.put("lookingFor", candidate.getLookingFor());
            if (candidate.getUser() != null) {
                Map<String, Object> userMap = new HashMap<>();
                userMap.put("id", candidate.getUser().getId());
                userMap.put("name", candidate.getUser().getName());
                userMap.put("rating", candidate.getUser().getRating());
                userMap.put("tradeCount", candidate.getUser().getTradeCount());
                itemMap.put("user", userMap);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("item", itemMap);
            result.put("matchScore", Math.round(matchScore * 100));
            result.put("fairnessScore", Math.round(fairnessScore * 100));
            result.put("preferenceScore", Math.round(preferenceScore * 100));
            result.put("valueDifference", Math.round(Math.abs(sourceValue - candidateValue)));
            scored.add(result);
        }

        // Sort by match score descending
        scored.sort((a, b) -> {
            long scoreA = (long) a.get("matchScore");
            long scoreB = (long) b.get("matchScore");
            return Long.compare(scoreB, scoreA);
        });

        // Return top 10
        return scored.stream().limit(10).collect(Collectors.toList());
    }

    private Set<String> extractTags(String text) {
        if (text == null || text.trim().isEmpty()) return new HashSet<>();
        return Arrays.stream(text.toLowerCase().split("[\\s,]+"))
                .filter(w -> w.length() > 2)
                .collect(Collectors.toSet());
    }

    private double jaccardSimilarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }
}