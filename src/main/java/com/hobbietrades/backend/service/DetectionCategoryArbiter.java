package com.hobbietrades.backend.service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Chooses Cameras vs Instruments when both Roboflow pipelines return detections.
 * Reduces false positives such as "mirrorless camera" on a violin photo.
 */
public final class DetectionCategoryArbiter {

    private static final double MIN_CONFIDENCE = 0.35;
    /** Max lead a generic camera label may have over a definite instrument before we trust the instrument. */
    private static final double GENERIC_CAMERA_OVERRIDE_GAP = 0.12;
    private static final double AMBIGUOUS_GAP = 0.12;

    private static final Set<String> DEFINITE_INSTRUMENT_TOKENS = Set.of(
            "violin", "cello", "guitar", "piano", "keyboard", "drum", "drums",
            "saxophone", "trumpet", "flute", "ukulele", "banjo", "harp",
            "clarinet", "oboe", "trombone", "tuba", "accordion", "harmonica",
            "bass guitar", "electric guitar", "acoustic guitar", "double bass",
            "mandolin", "sitar", "lute", "maracas"
    );

    private static final Set<String> CAMERA_BRAND_OR_LENS_TOKENS = Set.of(
            "canon", "nikon", "sony", "fujifilm", "panasonic", "olympus", "gopro",
            "dji", "leica", "sigma", "tamron", "pentax", "lens", "hero", "eos", "alpha"
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

        Hit cameraOverride = preferSpecificCameraOverWeakInstrument(cameras, instruments);
        if (cameraOverride != null) {
            return cameraOverride;
        }

        double cameraAggregate = aggregateCategoryScore(allHits, "Cameras");
        double instrumentAggregate = aggregateCategoryScore(allHits, "Instruments");
        if (Math.abs(cameraAggregate - instrumentAggregate) >= 0.10) {
            return cameraAggregate > instrumentAggregate ? cameras : instruments;
        }

        return cameras.confidence() >= instruments.confidence() ? cameras : instruments;
    }

    /** Show the picker only when both categories are genuinely close calls. */
    public static boolean isAmbiguous(Map<String, Hit> bestPerCategory, Hit winner) {
        Hit cameras = bestPerCategory.get("Cameras");
        Hit instruments = bestPerCategory.get("Instruments");
        if (cameras == null || instruments == null || winner == null) {
            return false;
        }

        if (preferDefiniteInstrumentOverGenericCamera(cameras, instruments) != null) {
            return false;
        }
        if (preferSpecificCameraOverWeakInstrument(cameras, instruments) != null) {
            return false;
        }

        return Math.abs(cameras.confidence() - instruments.confidence()) < AMBIGUOUS_GAP;
    }

    private static Hit preferDefiniteInstrumentOverGenericCamera(Hit cameras, Hit instruments) {
        if (!isDefiniteInstrument(instruments.className()) || !isGenericCameraLabel(cameras.className())) {
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

    private static Hit preferSpecificCameraOverWeakInstrument(Hit cameras, Hit instruments) {
        if (!isSpecificCamera(cameras.className()) || isDefiniteInstrument(instruments.className())) {
            return null;
        }
        if (cameras.confidence() >= 0.55 && cameras.confidence() - instruments.confidence() >= 0.08) {
            return cameras;
        }
        return null;
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

    static boolean isGenericCameraLabel(String className) {
        String normalized = normalize(className);
        if (normalized.isBlank() || isSpecificCamera(className)) {
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
