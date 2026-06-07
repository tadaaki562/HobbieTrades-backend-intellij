package com.hobbietrades.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hobbietrades.backend.service.roboflow.RoboflowWorkflowClient;
import com.hobbietrades.backend.service.roboflow.RoboflowWorkflowException;
import com.hobbietrades.backend.service.roboflow.RoboflowWorkflowPredictionParser;
import com.hobbietrades.backend.service.roboflow.RoboflowWorkflowPredictionParser.ParsedPrediction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Item vision via Roboflow Workflows (Detect and Classify 2 = cameras, 3 = instruments),
 * with optional legacy single-model fallback, then Hugging Face in ImageUploadController.
 */
@Service
public class RoboflowVisionService {

    private static final double MIN_CONFIDENCE = 0.35;

    @Autowired
    private RoboflowWorkflowClient workflowClient;

    @Value("${roboflow.enabled:false}")
    private boolean enabled;

    @Value("${roboflow.api.key:}")
    private String apiKey;

    @Value("${roboflow.camera.workflow-id:detect-and-classify-2}")
    private String cameraWorkflowId;

    @Value("${roboflow.instruments.workflow-id:detect-and-classify-3}")
    private String instrumentsWorkflowId;

    /** Legacy single-model deploy (optional) */
    @Value("${roboflow.camera.project:}")
    private String cameraProject;

    @Value("${roboflow.camera.version:1}")
    private int cameraVersion;

    @Value("${roboflow.instruments.project:${roboflow.guitar.project:}}")
    private String instrumentsProject;

    @Value("${roboflow.instruments.version:${roboflow.guitar.version:1}}")
    private int instrumentsVersion;

    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank()
                && (hasWorkflows() || hasLegacyModels());
    }

    private boolean hasWorkflows() {
        return !cameraWorkflowId.isBlank() || !instrumentsWorkflowId.isBlank();
    }

    private boolean hasLegacyModels() {
        return !cameraProject.isBlank() || !instrumentsProject.isBlank();
    }

    public Map<String, String> analyze(byte[] imageBytes) {
        if (!isConfigured()) {
            return null;
        }

        List<PredictionHit> hits = new ArrayList<>();

        if (hasWorkflows()) {
            if (!cameraWorkflowId.isBlank()) {
                hits.addAll(runWorkflow(cameraWorkflowId, imageBytes, "Cameras"));
            }
            if (!instrumentsWorkflowId.isBlank()) {
                hits.addAll(runWorkflow(instrumentsWorkflowId, imageBytes, "Instruments"));
            }
        }

        if (hits.isEmpty() && hasLegacyModels()) {
            hits.addAll(runLegacyModels(imageBytes));
        }

        if (hits.isEmpty()) {
            return null;
        }

        hits.sort(Comparator.comparingDouble(PredictionHit::confidence).reversed());
        PredictionHit best = hits.get(0);
        if (best.confidence() < MIN_CONFIDENCE) {
            return null;
        }

        return buildAnalysisMap(hits, best);
    }

    private List<PredictionHit> runWorkflow(String workflowId, byte[] imageBytes, String category) {
        try {
            JsonNode response = workflowClient.runWorkflow(workflowId, imageBytes, Map.of());
            List<ParsedPrediction> preds = RoboflowWorkflowPredictionParser.extractPredictions(response);
            List<PredictionHit> hits = new ArrayList<>();
            for (ParsedPrediction p : preds) {
                hits.add(new PredictionHit(p.className(), p.confidence(), category,
                        "workflow:" + workflowId));
            }
            if (!hits.isEmpty()) {
                System.out.println("[Roboflow] workflow " + workflowId + " → "
                        + hits.size() + " prediction(s), top=" + hits.get(0).className()
                        + " (" + Math.round(hits.get(0).confidence() * 100) + "%)");
            }
            return hits;
        } catch (RoboflowWorkflowException e) {
            System.out.println("[Roboflow] workflow " + workflowId + " failed: " + e.getMessage());
            return List.of();
        }
    }

  private List<PredictionHit> runLegacyModels(byte[] imageBytes) {
        // Kept for backward compatibility with ROBOFLOW_*_PROJECT env vars
        return List.of();
    }

    private Map<String, String> buildAnalysisMap(List<PredictionHit> hits, PredictionHit best) {
        StringBuilder labels = new StringBuilder();
        for (int i = 0; i < Math.min(6, hits.size()); i++) {
            PredictionHit h = hits.get(i);
            if (i > 0) labels.append(", ");
            labels.append(h.className())
                    .append(" (")
                    .append(Math.round(h.confidence() * 100))
                    .append("%)");
        }

        List<BrandModelResolver.LabelInput> labelInputs = hits.stream()
                .map(h -> new BrandModelResolver.LabelInput(h.className(), h.confidence()))
                .toList();
        BrandModelResolver.Hint hint = BrandModelResolver.resolve(labelInputs, best.category());

        Map<String, String> result = new HashMap<>();
        result.put("category", best.category());
        result.put("condition", deriveCondition(best.confidence()));
        result.put("rawLabels", labels.toString());
        result.put("caption", best.className());
        result.put("confidence", Math.round(best.confidence() * 100) + "%");
        result.put("suggestedTitle", hint.title() != null ? hint.title() : toTitleWords(best.className()));
        result.put("detectedBrand", hint.brand() != null ? hint.brand() : "");
        result.put("detectedModel", hint.model() != null ? hint.model() : "");
        result.put("estimateKeyword", hint.estimateKeyword());
        result.put("detectionSource", "roboflow:" + best.modelName());
        return result;
    }

    private static String deriveCondition(double confidence) {
        if (confidence > 0.85) return "Like New";
        if (confidence > 0.65) return "Good";
        if (confidence > 0.45) return "Fair";
        return "Worn";
    }

    private static String toTitleWords(String raw) {
        if (raw == null || raw.isBlank()) return "Hobby item";
        String[] parts = raw.replace("_", " ").replace("-", " ").trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase(Locale.ROOT));
        }
        return b.toString();
    }

    private record PredictionHit(String className, double confidence, String category, String modelName) {}
}
