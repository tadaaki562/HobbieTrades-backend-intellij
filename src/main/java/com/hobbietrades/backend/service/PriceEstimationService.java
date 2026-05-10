package com.hobbietrades.backend.service;

import com.hobbietrades.backend.repository.PriceReferenceRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class PriceEstimationService {

    @Autowired
    private PriceReferenceRepository priceReferenceRepository;

    private static final Map<String, Double> CONDITION_MULTIPLIERS = new HashMap<>();
    static {
        CONDITION_MULTIPLIERS.put("Like New", 0.80);
        CONDITION_MULTIPLIERS.put("Good", 0.65);
        CONDITION_MULTIPLIERS.put("Fair", 0.50);
        CONDITION_MULTIPLIERS.put("Worn", 0.30);
    }

    private static final Set<String> CATEGORY_WHITELIST = Set.of(
            "Cameras", "Instruments", "Sports", "Gaming", "Art", "Craft", "Other"
    );

    private static final Map<String, String> KEYWORD_CATEGORY_HINTS = new LinkedHashMap<>();
    static {
        KEYWORD_CATEGORY_HINTS.put("guitar", "Instruments");
        KEYWORD_CATEGORY_HINTS.put("piano", "Instruments");
        KEYWORD_CATEGORY_HINTS.put("violin", "Instruments");
        KEYWORD_CATEGORY_HINTS.put("drum", "Instruments");
        KEYWORD_CATEGORY_HINTS.put("canon", "Cameras");
        KEYWORD_CATEGORY_HINTS.put("nikon", "Cameras");
        KEYWORD_CATEGORY_HINTS.put("dslr", "Cameras");
        KEYWORD_CATEGORY_HINTS.put("mirrorless", "Cameras");
        KEYWORD_CATEGORY_HINTS.put("nintendo", "Gaming");
        KEYWORD_CATEGORY_HINTS.put("playstation", "Gaming");
        KEYWORD_CATEGORY_HINTS.put("xbox", "Gaming");
        KEYWORD_CATEGORY_HINTS.put("controller", "Gaming");
        KEYWORD_CATEGORY_HINTS.put("basketball", "Sports");
        KEYWORD_CATEGORY_HINTS.put("tennis", "Sports");
        KEYWORD_CATEGORY_HINTS.put("bike", "Sports");
        KEYWORD_CATEGORY_HINTS.put("skate", "Sports");
        KEYWORD_CATEGORY_HINTS.put("paint", "Art");
        KEYWORD_CATEGORY_HINTS.put("canvas", "Art");
        KEYWORD_CATEGORY_HINTS.put("brush", "Art");
        KEYWORD_CATEGORY_HINTS.put("sewing", "Craft");
        KEYWORD_CATEGORY_HINTS.put("crochet", "Craft");
        KEYWORD_CATEGORY_HINTS.put("knitting", "Craft");
        KEYWORD_CATEGORY_HINTS.put("yarn", "Craft");
    }

    public Map<String, Object> estimatePrice(String category, String condition, String keyword) {
        Map<String, Object> result = new HashMap<>();
        String safeCondition = normalizeCondition(condition);
        String safeCategory = normalizeCategory(category);
        String hintedCategory = inferCategoryFromKeyword(keyword);

        if ("Other".equals(safeCategory) && hintedCategory != null) {
            safeCategory = hintedCategory;
            result.put("categoryHintApplied", true);
            result.put("categoryHint", hintedCategory);
        }

        // Step 1 — Try keyword match first (most specific)
        if (keyword != null && !keyword.isEmpty()) {
            String[] words = keyword.toLowerCase().split(" ");
            for (String word : words) {
                if (word.length() < 3) continue; // skip short words
                var matches = priceReferenceRepository
                        .findByKeywordContainingIgnoreCaseAndConditionLabel(word, safeCondition);
                if (!matches.isEmpty()) {
                    double avg = matches.stream()
                            .mapToDouble(p -> p.getAvgPrice().doubleValue())
                            .average()
                            .orElse(0);
                    if (avg > 0) {
                        result.put("estimatedValue", Math.round(avg));
                        result.put("rangeLow", Math.round(avg * 0.85));
                        result.put("rangeHigh", Math.round(avg * 1.15));
                        result.put("condition", safeCondition);
                        result.put("category", safeCategory);
                        result.put("matchedKeyword", word);
                        result.put("sampleCount", matches.stream()
                                .map(p -> p.getSampleCount() == null ? 1 : p.getSampleCount())
                                .reduce(0, Integer::sum));
                        result.put("source", "E-commerce reference averages (keyword match)");
                        result.put("confidence", "High");
                        return result;
                    }
                }
            }
        }

        // Step 2 — Fall back to category average from reference rows
        List<com.hobbietrades.backend.model.PriceReference> categoryRows =
                priceReferenceRepository.findByCategoryAndConditionLabel(safeCategory, safeCondition);
        if (!categoryRows.isEmpty()) {
            double avgPrice = categoryRows.stream()
                    .mapToDouble(p -> p.getAvgPrice().doubleValue())
                    .average()
                    .orElse(0);
            int sampleCount = categoryRows.stream()
                    .map(p -> p.getSampleCount() == null ? 1 : p.getSampleCount())
                    .reduce(0, Integer::sum);
            result.put("estimatedValue", Math.round(avgPrice));
            result.put("rangeLow", Math.round(avgPrice * 0.85));
            result.put("rangeHigh", Math.round(avgPrice * 1.15));
            result.put("condition", safeCondition);
            result.put("category", safeCategory);
            result.put("sampleCount", sampleCount);
            result.put("source", "E-commerce reference averages (category fallback)");
            result.put("confidence", "Medium");
            return result;
        }

        // Step 3 — Last resort: condition multiplier
        Map<String, Double> categoryBasePrices = new HashMap<>();
        categoryBasePrices.put("Cameras", 15000.0);
        categoryBasePrices.put("Instruments", 6000.0);
        categoryBasePrices.put("Sports", 3000.0);
        categoryBasePrices.put("Gaming", 14000.0);
        categoryBasePrices.put("Art", 2500.0);
        categoryBasePrices.put("Craft", 1200.0);
        categoryBasePrices.put("Other", 2000.0);

        double basePrice = categoryBasePrices.getOrDefault(safeCategory, 2000.0);
        double multiplier = CONDITION_MULTIPLIERS.getOrDefault(safeCondition, 0.50);
        double estimated = Math.round(basePrice * multiplier);

        result.put("estimatedValue", estimated);
        result.put("rangeLow", Math.round(estimated * 0.85));
        result.put("rangeHigh", Math.round(estimated * 1.15));
        result.put("condition", safeCondition);
        result.put("category", safeCategory);
        result.put("source", "Rule-based fallback (no e-commerce reference found)");
        result.put("confidence", "Low");
        return result;
    }

    private String normalizeCondition(String condition) {
        if (condition == null) return "Good";
        String v = condition.trim();
        if (v.equalsIgnoreCase("like new") || v.equalsIgnoreCase("mint")) return "Like New";
        if (v.equalsIgnoreCase("good")) return "Good";
        if (v.equalsIgnoreCase("fair")) return "Fair";
        if (v.equalsIgnoreCase("worn") || v.equalsIgnoreCase("poor")) return "Worn";
        return "Good";
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) return "Other";
        for (String c : CATEGORY_WHITELIST) {
            if (c.equalsIgnoreCase(category.trim())) return c;
        }
        return "Other";
    }

    private String inferCategoryFromKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) return null;
        String normalized = keyword.toLowerCase();
        for (Map.Entry<String, String> entry : KEYWORD_CATEGORY_HINTS.entrySet()) {
            if (normalized.contains(entry.getKey())) return entry.getValue();
        }
        return null;
    }
}