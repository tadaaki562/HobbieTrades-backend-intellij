package com.hobbietrades.backend.service.roboflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Direct object-detection model calls (bypasses broken workflow steps).
 * Uses Serverless v2 infer endpoint when workflow classification fails.
 */
@Component
public class RoboflowDetectionModelClient {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Value("${roboflow.api.key:}")
    private String apiKey;

    @Value("${roboflow.workspace:hobbietrades-dataset}")
    private String workspace;

    @Value("${roboflow.serverless-base:https://serverless.roboflow.com}")
    private String serverlessBase;

    public List<RoboflowWorkflowPredictionParser.ParsedPrediction> detect(
            String projectSlug, int version, byte[] imageBytes) {

        if (apiKey == null || apiKey.isBlank() || projectSlug == null || projectSlug.isBlank()) {
            return List.of();
        }

        String slug = projectSlug.trim().replaceAll("^/+|/+$", "");
        RoboflowWorkflowException last = null;

        for (String url : inferUrls(slug, version)) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(45))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                Base64.getEncoder().encodeToString(imageBytes)))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return parseModelResponse(response.body());
                }
                last = new RoboflowWorkflowException(
                        "Detection model HTTP " + response.statusCode() + " at " + url, response.statusCode());
                if (response.statusCode() != 404) break;
            } catch (Exception e) {
                last = new RoboflowWorkflowException("Detection model call failed: " + e.getMessage(), e);
            }
        }

        if (last != null) {
            System.out.println("[Roboflow] detection model " + slug + "/" + version + " failed: " + last.getMessage());
        }
        return List.of();
    }

    private String[] inferUrls(String slug, int version) {
        String base = serverlessBase.replaceAll("/+$", "");
        String ws = workspace.trim().replaceAll("^/+|/+$", "");
        String key = URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
        return new String[]{
                base + "/infer/" + ws + "/" + slug + "/" + version + "?api_key=" + key,
                base + "/" + slug + "/" + version + "?api_key=" + key,
                "https://detect.roboflow.com/" + slug + "/" + version + "?api_key=" + key
        };
    }

    private List<RoboflowWorkflowPredictionParser.ParsedPrediction> parseModelResponse(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            return RoboflowWorkflowPredictionParser.extractPredictions(root);
        } catch (Exception e) {
            return List.of();
        }
    }
}
