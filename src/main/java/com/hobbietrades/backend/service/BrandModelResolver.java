package com.hobbietrades.backend.service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Infers brand / model from Hugging Face ViT labels for clearer titles and price lookups.
 */
public final class BrandModelResolver {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9\\s]");

    private static final Set<String> BRANDS = new LinkedHashSet<>(List.of(
            "fender", "gibson", "yamaha", "ibanez", "epiphone", "squier", "martin", "taylor",
            "roland", "korg", "casio", "kawai", "steinway", "gretsch", "prs", "jackson",
            "canon", "nikon", "sony", "fujifilm", "panasonic", "olympus", "gopro", "dji",
            "leica", "sigma", "tamron", "pentax",
            "nintendo", "playstation", "xbox", "microsoft", "valve", "steam",
            "apple", "samsung", "dell", "lenovo", "asus", "acer", "hp",
            "marshall", "boss", "mesa", "vox", "orange",
            "lego", "hasbro", "pokemon"
    ));

    /** Longer phrases first when matching. */
    private static final List<String> MODEL_PHRASES = List.of(
            "nintendo switch", "switch oled", "playstation 5", "playstation 4", "xbox series",
            "steam deck", "les paul", "stratocaster", "telecaster", "precision bass",
            "jazz bass", "mustang bass", "acoustic guitar", "electric guitar", "bass guitar",
            "grand piano", "upright piano", "digital piano", "mirrorless", "dslr",
            "eos r5", "eos r6", "eos 90d", "a7iii", "a7 iv", "d850", "d750", "hero 11", "hero 12",
            "macbook pro", "macbook air", "ipad pro", "galaxy s", "thinkpad",
            "strat", "tele", "p bass", "jazzmaster", "mustang", "sg standard",
            "ps5", "ps4", "xbox one", "3ds", "2ds"
    );

    private static final Map<String, String> BRAND_ALIASES = Map.of(
            "playstation", "Sony",
            "xbox", "Microsoft",
            "steam", "Valve",
            "eos", "Canon",
            "hero", "GoPro",
            "macbook", "Apple",
            "ipad", "Apple",
            "galaxy", "Samsung",
            "thinkpad", "Lenovo",
            "dslr", "Canon"
    );

    private BrandModelResolver() {}

    public record LabelInput(String label, double score) {}

    public record Hint(String brand, String model, String title, String estimateKeyword) {}

    public static Hint resolve(List<LabelInput> scores, String category) {
        if (scores == null || scores.isEmpty()) {
            return fallback(category, null);
        }

        StringBuilder corpus = new StringBuilder();
        int limit = Math.min(12, scores.size());
        for (int i = 0; i < limit; i++) {
            String norm = normalize(scores.get(i).label);
            if (!norm.isBlank()) {
                corpus.append(' ').append(norm);
            }
        }
        String text = corpus.toString().trim();

        String brandKey = findBrand(text);
        String modelKey = findModel(text);
        String itemType = findItemType(text, category);

        String brandDisplay = brandKey != null ? capitalizeBrand(brandKey) : null;
        String modelDisplay = modelKey != null ? toTitleWords(modelKey) : null;

        String title = composeTitle(brandDisplay, modelDisplay, itemType, category);
        String estimateKeyword = composeEstimateKeyword(brandDisplay, modelDisplay, itemType, category);

        return new Hint(brandDisplay, modelDisplay, title, estimateKeyword);
    }

    private static String findBrand(String text) {
        for (String b : BRANDS) {
            if (containsToken(text, b)) {
                return b;
            }
        }
        for (Map.Entry<String, String> e : BRAND_ALIASES.entrySet()) {
            if (containsToken(text, e.getKey())) {
                return e.getKey();
            }
        }
        return null;
    }

    private static String findModel(String text) {
        for (String phrase : MODEL_PHRASES) {
            if (text.contains(phrase)) {
                return phrase;
            }
        }
        return null;
    }

    private static String findItemType(String text, String category) {
        for (String phrase : MODEL_PHRASES) {
            if (phrase.contains("guitar") || phrase.contains("piano") || phrase.contains("bass")
                    || phrase.contains("camera") || phrase.contains("console") || phrase.contains("deck")) {
                if (text.contains(phrase)) {
                    return phrase;
                }
            }
        }
        if (text.contains("guitar")) return "guitar";
        if (text.contains("piano")) return "piano";
        if (text.contains("camera")) return "camera";
        if (text.contains("violin")) return "violin";
        if (text.contains("drum")) return "drum kit";
        if (category != null && !category.isBlank() && !"Other".equals(category)) {
            return category.toLowerCase(Locale.ROOT) + " item";
        }
        return null;
    }

    private static String composeTitle(String brand, String model, String itemType, String category) {
        if (brand != null && model != null) {
            String mLower = model.toLowerCase(Locale.ROOT);
            if (mLower.contains(brand.toLowerCase(Locale.ROOT))) {
                return toTitleWords(model);
            }
            return brand + " " + toTitleWords(model);
        }
        if (brand != null && itemType != null) {
            return brand + " " + toTitleWords(itemType);
        }
        if (model != null) {
            return toTitleWords(model);
        }
        if (itemType != null) {
            return toTitleWords(itemType);
        }
        return fallback(category, null).title();
    }

    private static String composeEstimateKeyword(String brand, String model, String itemType, String category) {
        StringBuilder k = new StringBuilder();
        if (brand != null) k.append(brand).append(' ');
        if (model != null) {
            k.append(model);
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
        if ("dj".equals(key)) return "DJI";
        if ("prs".equals(key)) return "PRS";
        if ("hp".equals(key)) return "HP";
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
