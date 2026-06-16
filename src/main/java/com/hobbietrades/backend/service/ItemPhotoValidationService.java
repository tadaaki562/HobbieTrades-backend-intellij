package com.hobbietrades.backend.service;

import com.hobbietrades.backend.util.HobbyCategories;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Ensures uploaded photos contain a detectable camera or musical instrument.
 */
@Service
public class ItemPhotoValidationService {

    private static final double MIN_CONFIDENCE = 0.35;

    @Autowired
    private RoboflowVisionService roboflowVisionService;

    @Value("${hobbietrades.validation.require-roboflow:false}")
    private boolean requireRoboflow;

    public record ValidationResult(
            String category,
            String className,
            String confidence,
            String detectionSource,
            Map<String, String> analysis) {}

    /**
     * Validates image bytes. Throws if not a camera or instrument.
     */
    public ValidationResult validate(byte[] imageBytes, ImageUploadControllerAdapter hfAnalyzer) {
        Map<String, String> roboflow = roboflowVisionService.analyze(imageBytes);
        if (roboflow != null && !roboflow.isEmpty()) {
            return fromAnalysis(roboflow, true);
        }

        if (requireRoboflow) {
            throw new ItemValidationException(
                    "Could not verify this photo with our AI models. Only cameras and musical instruments are allowed.");
        }

        if (hfAnalyzer == null) {
            throw new ItemValidationException(
                    "Could not verify this photo. Only cameras and musical instruments are allowed.");
        }

        Map<String, String> hf;
        try {
            hf = hfAnalyzer.analyzeWithHuggingFace(imageBytes);
        } catch (Exception e) {
            throw new ItemValidationException(
                    "Could not analyze this photo. Only cameras and musical instruments are allowed.");
        }
        if (hf == null || hf.isEmpty()) {
            throw new ItemValidationException(
                    "Could not analyze this photo. Only cameras and musical instruments are allowed.");
        }
        return fromAnalysis(hf, false);
    }

    private ValidationResult fromAnalysis(Map<String, String> analysis, boolean fromRoboflow) {
        String category = analysis.get("category");
        if (!HobbyCategories.isAllowed(category)) {
            throw new ItemValidationException(
                    "This photo is not a camera or musical instrument. HobbieTrades only accepts Cameras and Instruments.");
        }

        String conf = analysis.getOrDefault("confidence", "0%");
        double score = parseConfidence(conf);
        if (score < MIN_CONFIDENCE) {
            throw new ItemValidationException(
                    "We could not confidently detect a camera or instrument in this photo (confidence too low). Please use a clearer photo.");
        }

        String source = analysis.getOrDefault("detectionSource", fromRoboflow ? "roboflow" : "huggingface");
        if (!fromRoboflow && !source.startsWith("roboflow")) {
            String caption = analysis.getOrDefault("caption", "");
            if (BrandModelResolver.isNonItemLabel(caption) && !hasItemLabel(analysis.get("rawLabels"))) {
                throw new ItemValidationException(
                        "This photo does not show a camera or musical instrument. Please upload a photo of the item itself.");
            }
        }

        return new ValidationResult(
                category,
                analysis.getOrDefault("caption", ""),
                conf,
                source,
                analysis);
    }

    private static boolean hasItemLabel(String rawLabels) {
        if (rawLabels == null || rawLabels.isBlank()) return false;
        String lower = rawLabels.toLowerCase(Locale.ROOT);
        return lower.contains("guitar") || lower.contains("camera") || lower.contains("piano")
                || lower.contains("drum") || lower.contains("violin") || lower.contains("lens")
                || lower.contains("bass") || lower.contains("saxophone") || lower.contains("ukulele")
                || lower.contains("keyboard") || lower.contains("trumpet") || lower.contains("flute");
    }

    private static double parseConfidence(String conf) {
        if (conf == null) return 0;
        String num = conf.replace("%", "").trim();
        try {
            return Double.parseDouble(num) / 100.0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Lets validation service call package-private HF logic on ImageUploadController. */
    public interface ImageUploadControllerAdapter {
        Map<String, String> analyzeWithHuggingFace(byte[] imageBytes) throws Exception;
    }
}
