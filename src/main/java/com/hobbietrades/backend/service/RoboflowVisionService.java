package com.hobbietrades.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hobbietrades.backend.service.roboflow.RoboflowDetectionModelClient;
import com.hobbietrades.backend.service.roboflow.RoboflowWorkflowClient;
import com.hobbietrades.backend.service.roboflow.RoboflowWorkflowException;
import com.hobbietrades.backend.service.roboflow.RoboflowWorkflowPredictionParser;
import com.hobbietrades.backend.service.roboflow.RoboflowWorkflowPredictionParser.ParsedPrediction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Item vision via Roboflow Workflows, with direct detection-model fallback when a workflow
 * step fails (e.g. misconfigured car-colors classifier inside Detect and Classify 3).
 */
@Service
public class RoboflowVisionService {

    private static final double MIN_CONFIDENCE = 0.35;

    @Autowired
    private RoboflowWorkflowClient workflowClient;

    @Autowired
    private RoboflowDetectionModelClient detectionModelClient;

    @Value("${roboflow.enabled:false}")
    private boolean enabled;

    @Value("${roboflow.api.key:}")
    private String apiKey;

    @Value("${roboflow.camera.workflow-id:detect-and-classify-2}")
    private String cameraWorkflowId;

    @Value("${roboflow.instruments.workflow-id:detect-and-classify-3}")
    private String instrumentsWorkflowId;

    /** false = skip broken detect-and-classify-2 workflow; use camera-detection model directly */
    @Value("${roboflow.camera.use-workflow:false}")
    private boolean cameraUseWorkflow;

    @Value("${roboflow.instruments.use-workflow:true}")
    private boolean instrumentsUseWorkflow;

    @Value("${roboflow.workflow.fallback-on-error:true}")
    private boolean fallbackOnError;

    @Value("${roboflow.camera.project:}")
    private String cameraProject;

    @Value("${roboflow.camera.version:1}")
    private int cameraVersion;

    @Value("${roboflow.instruments.project:musical-instruments-detection-kemni-bzqvi}")
    private String instrumentsProject;

    @Value("${roboflow.instruments.version:2}")
    private int instrumentsVersion;

    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank()
                && (hasWorkflows() || hasDetectionModels());
    }

    private boolean hasWorkflows() {
        return !cameraWorkflowId.isBlank() || !instrumentsWorkflowId.isBlank();
    }

    private boolean hasDetectionModels() {
        return !cameraProject.isBlank() || !instrumentsProject.isBlank();
    }

    public Map<String, String> analyze(byte[] imageBytes) {
        if (!isConfigured()) {
            return null;
        }

        List<PredictionHit> hits = new ArrayList<>();

        hits.addAll(runCategory(cameraUseWorkflow, cameraWorkflowId, cameraProject, cameraVersion,
                imageBytes, "Cameras"));

        hits.addAll(runCategory(instrumentsUseWorkflow, instrumentsWorkflowId, instrumentsProject,
                instrumentsVersion, imageBytes, "Instruments"));

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

    private List<PredictionHit> runCategory(
            boolean useWorkflow, String workflowId, String detectionProject, int detectionVersion,
            byte[] imageBytes, String category) {

        if (useWorkflow && workflowId != null && !workflowId.isBlank()) {
            return runWorkflowOrFallback(workflowId, imageBytes, category, detectionProject, detectionVersion);
        }
        if (detectionProject != null && !detectionProject.isBlank()) {
            return runDetectionModel(detectionProject, detectionVersion, imageBytes, category);
        }
        return List.of();
    }

    private List<PredictionHit> runWorkflowOrFallback(
            String workflowId, byte[] imageBytes, String category,
            String fallbackProject, int fallbackVersion) {

        List<PredictionHit> fromWorkflow = runWorkflow(workflowId, imageBytes, category);
        if (!fromWorkflow.isEmpty()) {
            return fromWorkflow;
        }

        if (fallbackOnError && fallbackProject != null && !fallbackProject.isBlank()) {
            System.out.println("[Roboflow] workflow " + workflowId
                    + " unavailable — using detection model " + fallbackProject + "/" + fallbackVersion);
            return runDetectionModel(fallbackProject, fallbackVersion, imageBytes, category);
        }
        return List.of();
    }

    private List<PredictionHit> runWorkflow(String workflowId, byte[] imageBytes, String category) {
        try {
            JsonNode response = workflowClient.runWorkflow(workflowId, imageBytes, Map.of());
            return toHits(RoboflowWorkflowPredictionParser.extractPredictions(response), category,
                    "workflow:" + workflowId);
        } catch (RoboflowWorkflowException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("car-colors")) {
                System.out.println("[Roboflow] workflow " + workflowId
                        + " failed: classification step uses wrong model (car-colors). "
                        + "Fix workflow in Roboflow or use detection model fallback.");
            } else {
                System.out.println("[Roboflow] workflow " + workflowId + " failed: " + msg);
            }
            return List.of();
        }
    }

    private List<PredictionHit> runDetectionModel(
            String project, int version, byte[] imageBytes, String category) {

        List<ParsedPrediction> preds = detectionModelClient.detect(project, version, imageBytes);
        List<PredictionHit> hits = toHits(preds, category, "model:" + project + "/" + version);
        if (!hits.isEmpty()) {
            System.out.println("[Roboflow] model " + project + "/" + version + " → top="
                    + hits.get(0).className() + " (" + Math.round(hits.get(0).confidence() * 100) + "%)");
        }
        return hits;
    }

    private List<PredictionHit> toHits(List<ParsedPrediction> preds, String category, String source) {
        List<PredictionHit> hits = new ArrayList<>();
        for (ParsedPrediction p : preds) {
            hits.add(new PredictionHit(p.className(), p.confidence(), category, source));
        }
        if (!hits.isEmpty() && source.startsWith("workflow:")) {
            System.out.println("[Roboflow] " + source + " → " + hits.size() + " prediction(s), top="
                    + hits.get(0).className() + " (" + Math.round(hits.get(0).confidence() * 100) + "%)");
        }
        return hits;
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
