package com.hobbietrades.backend.service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Infers brand / model from Hugging Face ViT labels for clearer titles and price lookups.
 * Uses label confidence scores so a 100% "electric guitar" beats a 0% "acoustic guitar".
 */
public final class BrandModelResolver {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9\\s]");

    private static final Set<String> BRANDS = new LinkedHashSet<>(List.of(
            "fender", "gibson", "yamaha", "ibanez", "epiphone", "squier", "martin", "taylor",
            "roland", "korg", "casio", "kawai", "gretsch", "prs", "jackson",
            "canon", "nikon", "sony", "fujifilm", "panasonic", "olympus", "gopro", "dji",
            "leica", "sigma", "tamron", "pentax"
    ));

    /** Guitar / instrument types — chosen by highest label score, not list order. */
    private static final List<String> ITEM_TYPE_PHRASES = List.of(
            "electric guitar", "acoustic guitar", "bass guitar", "classical guitar",
            "grand piano", "upright piano", "digital piano", "drum kit", "violin", "cello",
            "saxophone", "trumpet", "flute", "ukulele",
            "mirrorless camera", "dslr camera", "digital camera", "action camera",
            "film camera", "instant camera", "camera lens", "camera"
    );

    private static final List<String> MODEL_PHRASES = List.of(
            "les paul", "stratocaster", "telecaster", "precision bass", "jazz bass",
            "mustang bass", "jazzmaster", "sg standard",
            "eos r5", "eos r6", "eos 90d", "a7iii", "a7 iv", "d850", "d750", "hero 11", "hero 12"
    );

    private static final Map<String, String> BRAND_ALIASES = Map.of(
            "eos", "Canon",
            "hero", "GoPro"
    );

    private BrandModelResolver() {}

    public record LabelInput(String label, double score) {}

    public record Hint(String brand, String model, String title, String estimateKeyword) {}

    public static Hint resolve(List<LabelInput> scores, String category) {
        if (scores == null || scores.isEmpty()) {
            return fallback(category, null);
        }

        String brandKey = findBestBrand(scores);
        String itemType = findBestPhrase(scores, ITEM_TYPE_PHRASES);
        String modelKey = findBestPhrase(scores, MODEL_PHRASES);

        // Model phrase must not contradict the winning item type (e.g. skip "acoustic" model when type is electric)
        if (itemType != null && modelKey != null && contradicts(itemType, modelKey)) {
            modelKey = null;
        }

        String brandDisplay = brandKey != null ? capitalizeBrand(brandKey) : null;
        String typeDisplay = itemType != null ? toTitleWords(itemType) : null;
        String modelDisplay = modelKey != null ? toTitleWords(modelKey) : typeDisplay;

        String title = composeTitle(brandDisplay, modelKey != null ? toTitleWords(modelKey) : null, typeDisplay, category);
        String estimateKeyword = composeEstimateKeyword(brandDisplay, itemType, modelKey, category);

        return new Hint(brandDisplay, modelDisplay, title, estimateKeyword);
    }

    private static boolean contradicts(String itemType, String modelKey) {
        if (itemType.contains("electric") && modelKey.contains("acoustic")) return true;
        if (itemType.contains("acoustic") && modelKey.contains("electric")) return true;
        return false;
    }

    private static String findBestBrand(List<LabelInput> scores) {
        String best = null;
        double bestScore = 0;
        for (LabelInput li : scores) {
            String norm = " " + normalize(li.label) + " ";
            for (String b : BRANDS) {
                if (containsToken(norm, b) && li.score > bestScore) {
                    bestScore = li.score;
                    best = b;
                }
            }
        }
        return best;
    }

    /**
     * Pick the phrase that appears in a label with the highest confidence score.
     */
    private static String findBestPhrase(List<LabelInput> scores, List<String> phrases) {
        String bestPhrase = null;
        double bestScore = -1;
        for (LabelInput li : scores) {
            String norm = normalize(li.label);
            for (String phrase : phrases) {
                if (labelContainsPhrase(norm, phrase) && li.score > bestScore) {
                    bestScore = li.score;
                    bestPhrase = phrase;
                }
            }
        }
        return bestPhrase;
    }

    private static boolean labelContainsPhrase(String normalizedLabel, String phrase) {
        return normalizedLabel.equals(phrase)
                || normalizedLabel.startsWith(phrase + " ")
                || normalizedLabel.endsWith(" " + phrase)
                || normalizedLabel.contains(" " + phrase + " ");
    }

    private static String composeTitle(String brand, String specificModel, String itemType, String category) {
        if (brand != null && specificModel != null) {
            return brand + " " + specificModel;
        }
        if (brand != null && itemType != null) {
            return brand + " " + itemType;
        }
        if (specificModel != null) {
            return specificModel;
        }
        if (itemType != null) {
            return itemType;
        }
        return fallback(category, null).title();
    }

    private static String composeEstimateKeyword(String brand, String itemType, String modelKey, String category) {
        StringBuilder k = new StringBuilder();
        if (brand != null) k.append(brand).append(' ');
        if (modelKey != null) {
            k.append(modelKey);
        } else if (itemType != null) {
            k.append(itemType);
        } else if (category != null && !"Other".equals(category)) {
            k.append(category);
        }
        String s = k.toString().trim().toLowerCase(Locale.ROOT);
        if (s.isBlank()) {
            return fallback(category, null).estimateKeyword();
        }
        return s;
    }

    private static Hint fallback(String category, String itemType) {
        String type = itemType != null ? toTitleWords(itemType)
                : (category != null && !category.isBlank() && !"Other".equals(category)
                ? category + " item" : "Hobby item");
        return new Hint(null, null, toTitleWords(type), type.toLowerCase(Locale.ROOT));
    }

    private static boolean containsToken(String text, String token) {
        return text.contains(" " + token + " ") || text.startsWith(token + " ")
                || text.endsWith(" " + token) || text.equals(token);
    }

    private static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.toLowerCase(Locale.ROOT).split(",")[0];
        s = NON_ALNUM.matcher(s).replaceAll(" ").replaceAll("\\s+", " ").trim();
        s = s.replace("mike", "microphone");
        return s;
    }

    private static String capitalizeBrand(String key) {
        if (BRAND_ALIASES.containsKey(key)) {
            return BRAND_ALIASES.get(key);
        }
        if ("gopro".equals(key)) return "GoPro";
        if ("dji".equals(key)) return "DJI";
        if ("prs".equals(key)) return "PRS";
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    private static String toTitleWords(String phrase) {
        if (phrase == null || phrase.isBlank()) return "Hobby item";
        String[] parts = phrase.trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            if (p.length() <= 3 && !p.equals("air") && !p.equals("pro")) {
                b.append(p.toUpperCase(Locale.ROOT));
            } else {
                b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
            }
        }
        return b.toString();
    }
}
