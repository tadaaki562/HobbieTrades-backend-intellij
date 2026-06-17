package com.hobbietrades.backend.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/** Maps internal AI analysis maps to API JSON field names. */
public final class AnalyzeResponseHelper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnalyzeResponseHelper() {}

    public static void putAnalysisFields(Map<String, Object> target, Map<String, String> ai) {
        target.put("detectedCategory", ai.get("category"));
        target.put("detectedCondition", ai.get("condition"));
        target.put("rawLabels", ai.get("rawLabels"));
        target.put("caption", ai.get("caption"));
        target.put("confidence", ai.get("confidence"));
        target.put("suggestedTitle", ai.get("suggestedTitle"));
        target.put("detectedBrand", ai.get("detectedBrand"));
        target.put("detectedModel", ai.get("detectedModel"));
        target.put("estimateKeyword", ai.get("estimateKeyword"));
        target.put("detectionSource", ai.getOrDefault("detectionSource", "roboflow"));

        if ("true".equals(ai.get("hasMultipleDetections")) && ai.get("detectionOptions") != null) {
            try {
                List<Map<String, Object>> options = MAPPER.readValue(
                        ai.get("detectionOptions"), new TypeReference<>() {});
                if (options != null && options.size() >= 2) {
                    target.put("hasMultipleDetections", true);
                    target.put("detectionOptions", options);
                }
            } catch (Exception ignored) {
                // omit picker if options cannot be parsed
            }
        }
    }
}
