package com.hobbietrades.backend.service;

import com.hobbietrades.backend.model.Item;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * MarketValueService — estimates second-hand value in Philippine Peso (₱).
 *
 * Strategy: Formula-based estimation using:
 *   1. Category baseline (realistic PH Carousell/OLX-derived midpoints)
 *   2. Condition multiplier
 *   3. Keyword price modifiers for premium brands/models in title/description
 *   4. Returns an estimate + confidence range (min/max)
 */
@Service
public class MarketValueService {

    /**
     * Category baselines in ₱ — "Good" condition second-hand midpoint.
     * Array index: [0]=Poor, [1]=Fair, [2]=Good, [3]=Mint
     */
    private static final Map<String, int[]> CATEGORY_BASELINES = new LinkedHashMap<>();
    static {
        CATEGORY_BASELINES.put("Instruments",  new int[]{500,  1500,  4500, 18000});
        CATEGORY_BASELINES.put("Photography",  new int[]{800,  2500,  7500, 22000});
        CATEGORY_BASELINES.put("Electronics",  new int[]{500,  1200,  4000, 14000});
        CATEGORY_BASELINES.put("Board Games",  new int[]{100,  350,   850,  2200});
        CATEGORY_BASELINES.put("Books",        new int[]{50,   120,   300,  700});
        CATEGORY_BASELINES.put("Art & Craft",  new int[]{150,  500,   1500, 5000});
        CATEGORY_BASELINES.put("Sports",       new int[]{250,  800,   2500, 8000});
        CATEGORY_BASELINES.put("Outdoor",      new int[]{400,  1200,  3500, 9000});
        CATEGORY_BASELINES.put("Collectibles", new int[]{200,  700,   2500, 10000});
        CATEGORY_BASELINES.put("General",      new int[]{200,  600,   1500, 4000});
    }

    private static final Map<String, Double> KEYWORD_MULTIPLIERS = new LinkedHashMap<>();
    static {
        KEYWORD_MULTIPLIERS.put("fender",             2.8);
        KEYWORD_MULTIPLIERS.put("gibson",             3.5);
        KEYWORD_MULTIPLIERS.put("yamaha",             1.8);
        KEYWORD_MULTIPLIERS.put("roland",             2.0);
        KEYWORD_MULTIPLIERS.put("casio",              1.2);
        KEYWORD_MULTIPLIERS.put("ibanez",             2.0);
        KEYWORD_MULTIPLIERS.put("martin",             3.0);
        KEYWORD_MULTIPLIERS.put("taylor",             3.2);
        KEYWORD_MULTIPLIERS.put("canon",              1.8);
        KEYWORD_MULTIPLIERS.put("nikon",              1.9);
        KEYWORD_MULTIPLIERS.put("sony",               2.0);
        KEYWORD_MULTIPLIERS.put("fujifilm",           2.2);
        KEYWORD_MULTIPLIERS.put("dslr",               2.5);
        KEYWORD_MULTIPLIERS.put("mirrorless",         2.8);
        KEYWORD_MULTIPLIERS.put("gopro",              1.9);
        KEYWORD_MULTIPLIERS.put("apple",              2.5);
        KEYWORD_MULTIPLIERS.put("macbook",            3.0);
        KEYWORD_MULTIPLIERS.put("ipad",               2.2);
        KEYWORD_MULTIPLIERS.put("samsung",            1.8);
        KEYWORD_MULTIPLIERS.put("nintendo",           2.0);
        KEYWORD_MULTIPLIERS.put("playstation",        2.2);
        KEYWORD_MULTIPLIERS.put("ps5",                3.5);
        KEYWORD_MULTIPLIERS.put("ps4",                2.0);
        KEYWORD_MULTIPLIERS.put("xbox",               2.0);
        KEYWORD_MULTIPLIERS.put("gaming laptop",      2.5);
        KEYWORD_MULTIPLIERS.put("mechanical keyboard",1.6);
        KEYWORD_MULTIPLIERS.put("bose",               2.2);
        KEYWORD_MULTIPLIERS.put("jbl",                1.5);
        KEYWORD_MULTIPLIERS.put("original",           1.3);
        KEYWORD_MULTIPLIERS.put("complete",           1.4);
        KEYWORD_MULTIPLIERS.put("sealed",             1.8);
        KEYWORD_MULTIPLIERS.put("catan",              1.6);
        KEYWORD_MULTIPLIERS.put("lego",               2.5);
        KEYWORD_MULTIPLIERS.put("rare",               2.0);
        KEYWORD_MULTIPLIERS.put("first edition",      3.0);
        KEYWORD_MULTIPLIERS.put("signed",             2.5);
        KEYWORD_MULTIPLIERS.put("hardcover",          1.5);
        KEYWORD_MULTIPLIERS.put("textbook",           1.8);
        KEYWORD_MULTIPLIERS.put("manga",              1.3);
        KEYWORD_MULTIPLIERS.put("nike",               1.5);
        KEYWORD_MULTIPLIERS.put("adidas",             1.4);
        KEYWORD_MULTIPLIERS.put("spalding",           1.6);
        KEYWORD_MULTIPLIERS.put("mountain bike",      3.0);
        KEYWORD_MULTIPLIERS.put("carbon",             2.5);
        KEYWORD_MULTIPLIERS.put("professional",       1.8);
    }

    /**
     * Estimate market value for an item.
     * FIX: item.getCondition() → item.getConditionLabel() throughout
     */
    public EstimateResult estimate(Item item) {
        String category  = item.getCategory()     != null ? item.getCategory()                : "General";
        // FIX: was item.getCondition() — field is conditionLabel, getter is getConditionLabel()
        String condition = item.getConditionLabel() != null ? normalizeCondition(item.getConditionLabel()) : "Good";
        String title     = item.getTitle()         != null ? item.getTitle().toLowerCase()    : "";
        String desc      = item.getDescription()   != null ? item.getDescription().toLowerCase() : "";
        String combined  = title + " " + desc;

        int[] baseline = CATEGORY_BASELINES.getOrDefault(category, CATEGORY_BASELINES.get("General"));
        double baseValue = switch (condition) {
            case "Like New" -> baseline[3];
            case "Good" -> baseline[2];
            case "Fair" -> baseline[1];
            case "Worn" -> baseline[0];
            default     -> baseline[2];
        };

        double bestMultiplier = 1.0;
        String matchedKeyword = null;
        for (Map.Entry<String, Double> entry : KEYWORD_MULTIPLIERS.entrySet()) {
            if (combined.contains(entry.getKey()) && entry.getValue() > bestMultiplier) {
                bestMultiplier = entry.getValue();
                matchedKeyword = entry.getKey();
            }
        }

        double completenessModifier = 1.0;
        if (combined.contains("complete") || combined.contains("with box") || combined.contains("original box")) {
            completenessModifier = 1.25;
        } else if (combined.contains("broken") || combined.contains("for parts") || combined.contains("defective")) {
            completenessModifier = 0.4;
        } else if (combined.contains("refurbished") || combined.contains("restored")) {
            completenessModifier = 0.85;
        }

        double finalValue = baseValue * bestMultiplier * completenessModifier;

        int estimate    = (int) Math.round(finalValue);
        int minEstimate = (int) Math.round(finalValue * 0.70);
        int maxEstimate = (int) Math.round(finalValue * 1.35);

        String confidence;
        List<String> factors = new ArrayList<>();
        if (bestMultiplier > 1.0) {
            factors.add(capitalize(matchedKeyword) + " brand detected");
            confidence = "High";
        } else {
            confidence = "Medium";
        }
        if (completenessModifier > 1.0) factors.add("Complete with accessories");
        if (completenessModifier < 1.0) factors.add("Condition/completeness penalty applied");
        factors.add(condition + " condition — " + category + " category");

        String explanation = String.format(
                "Based on PH second-hand market data. Estimated from %s %s baseline (₱%s), condition: %s%s.",
                condition, category,
                formatPeso(baseline[conditionIndex(condition)]),
                condition,
                matchedKeyword != null ? ", brand boost: " + capitalize(matchedKeyword) : ""
        );

        return new EstimateResult(estimate, minEstimate, maxEstimate, confidence, explanation, factors);
    }

    /**
     * Convenience method for API endpoint — builds a dummy Item and delegates.
     * FIX: was dummy.setCondition() → dummy.setConditionLabel()
     */
    public EstimateResult estimateFromFields(String category, String condition,
                                             String title, String description) {
        Item dummy = new Item();
        dummy.setCategory(category);
        // FIX: was dummy.setCondition(condition)
        dummy.setConditionLabel(condition);
        dummy.setTitle(title);
        dummy.setDescription(description);
        return estimate(dummy);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int conditionIndex(String condition) {
        return switch (condition) {
            case "Worn" -> 0; case "Fair" -> 1; case "Good" -> 2; case "Like New" -> 3;
            default -> 2;
        };
    }

    private String normalizeCondition(String condition) {
        if (condition == null) return "Good";
        if ("mint".equalsIgnoreCase(condition) || "like new".equalsIgnoreCase(condition)) return "Like New";
        if ("good".equalsIgnoreCase(condition)) return "Good";
        if ("fair".equalsIgnoreCase(condition)) return "Fair";
        if ("poor".equalsIgnoreCase(condition) || "worn".equalsIgnoreCase(condition)) return "Worn";
        return "Good";
    }

    private String formatPeso(int value) {
        return String.format("%,d", value);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ── Result DTO ────────────────────────────────────────────────────────────

    public static class EstimateResult {
        public final int estimate;
        public final int minEstimate;
        public final int maxEstimate;
        public final String confidence;
        public final String explanation;
        public final List<String> factors;

        public EstimateResult(int estimate, int minEstimate, int maxEstimate,
                              String confidence, String explanation, List<String> factors) {
            this.estimate    = estimate;
            this.minEstimate = minEstimate;
            this.maxEstimate = maxEstimate;
            this.confidence  = confidence;
            this.explanation = explanation;
            this.factors     = factors;
        }

        public String getRangeFormatted() {
            return String.format("₱%,d – ₱%,d", minEstimate, maxEstimate);
        }
    }
}