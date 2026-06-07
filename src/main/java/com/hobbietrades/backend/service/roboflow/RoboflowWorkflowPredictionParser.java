package com.hobbietrades.backend.service.roboflow;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * Defensively extracts class + confidence from Roboflow workflow responses.
 * Output key names come from the workflow definition (e.g. predictions, detection_predictions).
 */
public final class RoboflowWorkflowPredictionParser {

    private static final List<String> PREDICTION_FIELD_NAMES = List.of(
            "predictions",
            "detection_predictions",
            "classification_predictions"
    );

    private RoboflowWorkflowPredictionParser() {}

    public record ParsedPrediction(String className, double confidence) {}

    public static List<ParsedPrediction> extractPredictions(JsonNode responseRoot) {
        if (responseRoot == null || responseRoot.isNull()) {
            return List.of();
        }

        List<JsonNode> outputObjects = collectOutputObjects(responseRoot);
        List<ParsedPrediction> found = new ArrayList<>();

        for (JsonNode output : outputObjects) {
            for (String field : PREDICTION_FIELD_NAMES) {
                appendFromNode(output.path(field), found);
            }
            walkForPredictionArrays(output, found, 0);
        }

        // De-dupe by class keeping highest confidence
        Map<String, ParsedPrediction> best = new LinkedHashMap<>();
        for (ParsedPrediction p : found) {
            ParsedPrediction existing = best.get(p.className().toLowerCase(Locale.ROOT));
            if (existing == null || p.confidence() > existing.confidence()) {
                best.put(p.className().toLowerCase(Locale.ROOT), p);
            }
        }
        List<ParsedPrediction> sorted = new ArrayList<>(best.values());
        sorted.sort(Comparator.comparingDouble(ParsedPrediction::confidence).reversed());
        return sorted;
    }

    public static boolean hasPredictionOutputs(JsonNode responseRoot) {
        return !extractPredictions(responseRoot).isEmpty()
                || !collectOutputObjects(responseRoot).isEmpty();
    }

    private static List<JsonNode> collectOutputObjects(JsonNode root) {
        List<JsonNode> out = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(out::add);
            return out;
        }
        JsonNode outputs = root.path("outputs");
        if (outputs.isArray()) {
            outputs.forEach(out::add);
        }
        if (out.isEmpty() && root.isObject()) {
            out.add(root);
        }
        return out;
    }

    private static void walkForPredictionArrays(JsonNode node, List<ParsedPrediction> found, int depth) {
        if (node == null || node.isNull() || depth > 6) return;

        if (node.isArray()) {
            for (JsonNode child : node) {
                if (looksLikePrediction(child)) {
                    addPrediction(child, found);
                } else {
                    walkForPredictionArrays(child, found, depth + 1);
                }
            }
            return;
        }

        if (node.isObject()) {
            if (looksLikePrediction(node)) {
                addPrediction(node, found);
            }
            node.fields().forEachRemaining(e -> {
                String key = e.getKey().toLowerCase(Locale.ROOT);
                if (key.contains("image") || key.equals("output_image") || key.equals("dynamic_crop")) {
                    return; // skip large blobs
                }
                walkForPredictionArrays(e.getValue(), found, depth + 1);
            });
        }
    }

    private static void appendFromNode(JsonNode node, List<ParsedPrediction> found) {
        if (node == null || node.isNull()) return;
        if (node.isArray()) {
            node.forEach(n -> addPrediction(n, found));
        } else if (looksLikePrediction(node)) {
            addPrediction(node, found);
        }
    }

    private static boolean looksLikePrediction(JsonNode node) {
        if (node == null || !node.isObject()) return false;
        boolean hasClass = node.has("class") || node.has("class_name") || node.has("top");
        boolean hasConf = node.has("confidence") || node.has("score");
        return hasClass && hasConf;
    }

    private static void addPrediction(JsonNode node, List<ParsedPrediction> found) {
        String cls = firstText(node, "class", "class_name", "top", "label", "predicted_class");
        double conf = firstDouble(node, "confidence", "score", "probability");
        if (cls == null || cls.isBlank() || conf <= 0) return;
        found.add(new ParsedPrediction(cls.trim(), conf));
    }

    private static String firstText(JsonNode node, String... keys) {
        for (String k : keys) {
            if (node.has(k) && !node.path(k).asText("").isBlank()) {
                return node.path(k).asText();
            }
        }
        return null;
    }

    private static double firstDouble(JsonNode node, String... keys) {
        for (String k : keys) {
            if (node.has(k) && node.path(k).isNumber()) {
                return node.path(k).asDouble();
            }
        }
        return 0;
    }
}
