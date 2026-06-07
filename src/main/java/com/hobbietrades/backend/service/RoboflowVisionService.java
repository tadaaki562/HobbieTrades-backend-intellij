package com.hobbietrades.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Calls your Roboflow camera + instruments models (Serverless v2 API).
 * Configure project slugs in application.properties after deploying models in Roboflow.
 */
@Service
public class RoboflowVisionService {

    private static final double MIN_CONFIDENCE = 0.35;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${roboflow.enabled:false}")
    private boolean enabled;

    @Value("${roboflow.api.key:}")
    private String apiKey;

    /** e.g. workspace/camera-detection (no leading slash) */
    @Value("${roboflow.camera.project:}")
    private String cameraProject;

    @Value("${roboflow.camera.version:1}")
    private int cameraVersion;

    @Value("${roboflow.instruments.project:${roboflow.guitar.project:}}")
    private String instrumentsProject;

    @Value("${roboflow.instruments.version:${roboflow.guitar.version:1}}")
    private int instrumentsVersion;

    @Value("${roboflow.serverless-base:https://serverless.roboflow.com}")
    private String serverlessBase;

    public boolean isConfigured() {
        return enabled
                && apiKey != null && !apiKey.isBlank()
                && (!cameraProject.isBlank() || !instrumentsProject.isBlank());
    }

    /**
     * Runs camera + instruments models; returns merged analysis map compatible with ImageUploadController.runAI().
     * Returns null when Roboflow is off or no confident detection.
     */
    public Map<String, String> analyze(byte[] imageBytes) {
        if (!isConfigured()) {
            return null;
        }

        List<PredictionHit> hits = new ArrayList<>();
        if (!cameraProject.isBlank()) {
            hits.addAll(callModel(cameraProject, cameraVersion, imageBytes, "Cameras"));
        }
        if (!instrumentsProject.isBlank()) {
            hits.addAll(callModel(instrumentsProject, instrumentsVersion, imageBytes, "Instruments"));
        }

        if (hits.isEmpty()) {
            return null;
        }

        hits.sort(Comparator.comparingDouble(PredictionHit::confidence).reversed());
        PredictionHit best = hits.get(0);
        if (best.confidence() < MIN_CONFIDENCE) {
            return null;
        }

        String title = toTitleWords(best.className());
        StringBuilder labels = new StringBuilder();
        for (int i = 0; i < Math.min(6, hits.size()); i++) {
            PredictionHit h = hits.get(i);
            if (i > 0) labels.append(", ");
            labels.append(h.className())
                    .append(" (")
                    .append(Math.round(h.confidence() * 100))
                    .append("%)");
        }

        String condition = deriveCondition(best.confidence());
        List<BrandModelResolver.LabelInput> labelInputs = hits.stream()
                .map(h -> new BrandModelResolver.LabelInput(h.className(), h.confidence()))
                .toList();
        BrandModelResolver.Hint hint = BrandModelResolver.resolve(labelInputs, best.category());

        Map<String, String> result = new HashMap<>();
        result.put("category", best.category());
        result.put("condition", condition);
        result.put("rawLabels", labels.toString());
        result.put("caption", best.className());
        result.put("confidence", Math.round(best.confidence() * 100) + "%");
        result.put("suggestedTitle", hint.title() != null ? hint.title() : title);
        result.put("detectedBrand", hint.brand() != null ? hint.brand() : "");
        result.put("detectedModel", hint.model() != null ? hint.model() : "");
        result.put("estimateKeyword", hint.estimateKeyword());
        result.put("detectionSource", "roboflow:" + best.modelName());
        return result;
    }

    private List<PredictionHit> callModel(String project, int version, byte[] imageBytes, String category) {
        try {
            String b64 = Base64.getEncoder().encodeToString(imageBytes);
            String slug = project.trim().replaceAll("^/+", "");
            String url = serverlessBase.replaceAll("/+$", "") + "/"
                    + slug + "/" + version
                    + "?api_key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(25))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(45))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(b64))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                System.out.println("[Roboflow] " + slug + "/" + version + " HTTP " + response.statusCode());
                return List.of();
            }

            return parsePredictions(response.body(), category, slug);
        } catch (Exception e) {
            System.out.println("[Roboflow] call failed for " + project + ": " + e.getMessage());
            return List.of();
        }
    }

    private List<PredictionHit> parsePredictions(String json, String category, String modelName) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<PredictionHit> out = new ArrayList<>();

            JsonNode predictions = root.path("predictions");
            if (predictions.isArray() && !predictions.isEmpty()) {
                for (JsonNode p : predictions) {
                    String cls = p.path("class").asText("");
                    if (cls.isBlank()) cls = p.path("top").asText("");
                    double conf = p.path("confidence").asDouble(0);
                    if (!cls.isBlank() && conf > 0) {
                        out.add(new PredictionHit(cls, conf, category, modelName));
                    }
                }
                return out;
            }

            // Classification-style single top result
            String top = root.path("top").asText("");
            double conf = root.path("confidence").asDouble(0);
            if (!top.isBlank() && conf > 0) {
                out.add(new PredictionHit(top, conf, category, modelName));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
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
