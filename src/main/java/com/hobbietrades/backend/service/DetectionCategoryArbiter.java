package com.hobbietrades.backend.service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Chooses Cameras vs Instruments when both Roboflow pipelines return detections.
 * Handles cross-category false positives (e.g. mirrorless on a violin, guitar in background of a camera photo).
 */
public final class DetectionCategoryArbiter {

    private static final double MIN_CONFIDENCE = 0.35;
    /** Max lead a generic camera label may have over a definite instrument before we trust the instrument. */
    private static final double GENERIC_CAMERA_OVERRIDE_GAP = 0.12;
    /** Instrument may lead by this much and still lose to a strong foreground camera signal. */
    private static final double STRONG_CAMERA_OVERRIDE_GAP = 0.20;

    private static final Set<String> DEFINITE_INSTRUMENT_TOKENS = Set.of(
            "violin", "cello", "guitar", "piano", "keyboard", "drum", "drums",
            "saxophone", "trumpet", "flute", "ukulele", "banjo", "harp",
            "clarinet", "oboe", "trombone", "tuba", "accordion", "harmonica",
            "bass guitar", "electric guitar", "acoustic guitar", "classical guitar",
            "double bass", "mandolin", "sitar", "lute", "maracas"
    );

    private static final Set<String> CAMERA_BRAND_OR_LENS_TOKENS = Set.of(
            "canon", "nikon", "sony", "fujifilm", "panasonic", "olympus", "gopro",
            "dji", "leica", "sigma", "tamron", "pentax", "konica", "minolta", "ricoh",
            "kodak", "polaroid", "hasselblad", "contax", "yashica", "lens", "hero",
            "eos", "alpha", "instax"
    );

    private static final Set<String> STRONG_CAMERA_TYPE_TOKENS = Set.of(
            "film camera", "instant camera", "reflex camera", "camera lens",
            "action camera", "camcorder", "telephoto", "wide angle"
    );

    public record Hit(String className, double confidence, String category) {}

    private DetectionCategoryArbiter() {}

    public static Map<String, Hit> bestPerCategory(List<Hit> hits) {
        Map<String, Hit> best = new LinkedHashMap<>();
        for (Hit h : hits) {
            if (h.confidence() < MIN_CONFIDENCE || BrandModelResolver.isNonItemLabel(h.className())) {
                continue;
            }
            best.merge(h.category(), h, (a, b) -> a.confidence() >= b.confidence() ? a : b);
        }
        return best;
    }

    public static Hit resolveWinner(Map<String, Hit> bestPerCategory, List<Hit> allHits) {
        Hit cameras = bestPerCategory.get("Cameras");
        Hit instruments = bestPerCategory.get("Instruments");

        if (cameras == null && instruments == null) {
            return null;
        }
        if (cameras == null) {
            return instruments;
        }
        if (instruments == null) {
            return cameras;
        }

        Hit instrumentOverride = preferDefiniteInstrumentOverGenericCamera(cameras, instruments);
        if (instrumentOverride != null) {
            return instrumentOverride;
        }

        Hit cameraOverride = preferStrongCameraOverBackgroundInstrument(cameras, instruments, allHits);
        if (cameraOverride != null) {
            return cameraOverride;
        }

        double cameraScore = weightedCategoryScore(allHits, "Cameras");
        double instrumentScore = weightedCategoryScore(allHits, "Instruments");
        if (Math.abs(cameraScore - instrumentScore) >= 0.08) {
            return cameraScore > instrumentScore ? cameras : instruments;
        }

        return cameras.confidence() >= instruments.confidence() ? cameras : instruments;
    }

    /** Offer the picker whenever both hobby categories were detected. */
    public static boolean shouldOfferPicker(Map<String, Hit> bestPerCategory) {
        return bestPerCategory.containsKey("Cameras") && bestPerCategory.containsKey("Instruments");
    }

    private static Hit preferDefiniteInstrumentOverGenericCamera(Hit cameras, Hit instruments) {
        if (!isDefiniteInstrument(instruments.className())) {
            return null;
        }
        if (!isGenericCameraLabel(cameras.className()) || isStrongCameraSignal(cameras.className())) {
            return null;
        }
        double gap = cameras.confidence() - instruments.confidence();
        if (instruments.confidence() >= 0.50 && gap <= GENERIC_CAMERA_OVERRIDE_GAP) {
            return instruments;
        }
        if (instruments.confidence() >= 0.72 && gap <= 0.18) {
            return instruments;
        }
        return null;
    }

    /** Foreground camera (brand / film / lens) beats a background instrument in frame. */
    private static Hit preferStrongCameraOverBackgroundInstrument(
            Hit cameras, Hit instruments, List<Hit> allHits) {

        if (!isDefiniteInstrument(instruments.className())) {
            return null;
        }
        if (!isStrongCameraSignal(cameras.className())) {
            return null;
        }

        double gap = instruments.confidence() - cameras.confidence();
        double cameraScore = weightedCategoryScore(allHits, "Cameras");
        double instrumentScore = weightedCategoryScore(allHits, "Instruments");

        if (cameras.confidence() >= 0.45 && gap <= STRONG_CAMERA_OVERRIDE_GAP) {
            return cameras;
        }
        if (cameras.confidence() >= 0.40 && cameraScore >= instrumentScore * 0.88) {
            return cameras;
        }
        if (cameraScore >= instrumentScore * 1.05 && cameras.confidence() >= 0.40) {
            return cameras;
        }
        return null;
    }

    private static double weightedCategoryScore(List<Hit> hits, String category) {
        double aggregate = aggregateCategoryScore(hits, category);
        Hit best = hits.stream()
                .filter(h -> category.equals(h.category()))
                .filter(h -> !BrandModelResolver.isNonItemLabel(h.className()))
                .max(Comparator.comparingDouble(Hit::confidence))
                .orElse(null);
        if (best == null) {
            return aggregate;
        }
        double bonus = 0.0;
        if ("Cameras".equals(category) && isStrongCameraSignal(best.className())) {
            bonus += 0.12;
        }
        if ("Instruments".equals(category) && isDefiniteInstrument(best.className())) {
            bonus += 0.08;
        }
        return aggregate + bonus;
    }

    private static double aggregateCategoryScore(List<Hit> hits, String category) {
        return hits.stream()
                .filter(h -> category.equals(h.category()))
                .filter(h -> !BrandModelResolver.isNonItemLabel(h.className()))
                .sorted(Comparator.comparingDouble(Hit::confidence).reversed())
                .limit(3)
                .mapToDouble(Hit::confidence)
                .sum();
    }

    static boolean isDefiniteInstrument(String className) {
        String normalized = normalize(className);
        if (normalized.isBlank()) {
            return false;
        }
        for (String token : DEFINITE_INSTRUMENT_TOKENS) {
            if (containsToken(normalized, token)) {
                return true;
            }
        }
        return false;
    }

    static boolean isStrongCameraSignal(String className) {
        if (isSpecificCamera(className)) {
            return true;
        }
        String normalized = normalize(className);
        for (String token : STRONG_CAMERA_TYPE_TOKENS) {
            if (containsToken(normalized, token)) {
                return true;
            }
        }
        return false;
    }

    static boolean isGenericCameraLabel(String className) {
        String normalized = normalize(className);
        if (normalized.isBlank() || isStrongCameraSignal(className)) {
            return false;
        }
        return normalized.contains("camera")
                || normalized.contains("camcorder")
                || normalized.contains("dslr")
                || normalized.contains("mirrorless");
    }

    static boolean isSpecificCamera(String className) {
        String normalized = normalize(className);
        for (String token : CAMERA_BRAND_OR_LENS_TOKENS) {
            if (containsToken(normalized, token)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean containsToken(String text, String token) {
        return text.equals(token)
                || text.startsWith(token + " ")
                || text.endsWith(" " + token)
                || text.contains(" " + token + " ");
    }
}
