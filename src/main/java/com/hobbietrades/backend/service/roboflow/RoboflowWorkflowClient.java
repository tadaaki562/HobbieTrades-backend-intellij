package com.hobbietrades.backend.service.roboflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Runs Roboflow Serverless workflows (Detect and Classify 2 / 3).
 *
 * @see <a href="https://docs.roboflow.com/developer/rest-api/run-a-model-on-an-image">Roboflow Serverless v2</a>
 */
@Component
public class RoboflowWorkflowClient {

    private static final int MAX_ATTEMPTS = 3;

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

    @Value("${roboflow.workflow.request-timeout-seconds:45}")
    private int requestTimeoutSeconds;

    /**
     * When false, Roboflow reloads the latest published workflow instead of a cached definition
     * (server-side cache TTL is ~15 minutes).
     */
    @Value("${roboflow.workflow.use-cache:false}")
    private boolean useCache;

    /**
     * POST workflow with base64 image input named {@code image} (WorkflowImage).
     */
    public JsonNode runWorkflow(String workflowId, byte[] imageBytes, Map<String, Object> parameters) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new RoboflowWorkflowException("ROBOFLOW_API_KEY is not set");
        }
        if (workflowId == null || workflowId.isBlank()) {
            throw new RoboflowWorkflowException("workflow_id is required");
        }

        String body = buildRequestBody(imageBytes, parameters);
        RoboflowWorkflowException lastError = null;

        for (String url : workflowUrls(workflowId)) {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();

                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    int code = response.statusCode();

                    if (code >= 200 && code < 300) {
                        return objectMapper.readTree(response.body());
                    }

                    if (code == 404) {
                        lastError = new RoboflowWorkflowException(
                                "Workflow not found at " + url + " (HTTP 404)", code);
                        break; // try alternate URL pattern
                    }

                    if (code == 429 || code >= 500) {
                        backoff(attempt);
                        lastError = new RoboflowWorkflowException(
                                "Roboflow workflow HTTP " + code + ": " + truncate(response.body()), code);
                        continue;
                    }

                    throw new RoboflowWorkflowException(
                            "Roboflow workflow HTTP " + code + ": " + truncate(response.body()), code);

                } catch (RoboflowWorkflowException e) {
                    lastError = e;
                    if (e.getStatusCode() == 404) break;
                    if (attempt < MAX_ATTEMPTS && (e.getStatusCode() == 429 || e.getStatusCode() >= 500)) {
                        backoff(attempt);
                        continue;
                    }
                    throw e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RoboflowWorkflowException("Roboflow workflow interrupted", e);
                } catch (Exception e) {
                    lastError = new RoboflowWorkflowException("Roboflow workflow failed: " + e.getMessage(), e);
                    if (attempt < MAX_ATTEMPTS) {
                        backoff(attempt);
                    }
                }
            }
        }

        throw lastError != null ? lastError : new RoboflowWorkflowException("Roboflow workflow failed");
    }

    private String buildRequestBody(byte[] imageBytes, Map<String, Object> parameters) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("api_key", apiKey);
            root.put("use_cache", useCache);

            ObjectNode inputs = objectMapper.createObjectNode();
            ObjectNode image = objectMapper.createObjectNode();
            image.put("type", "base64");
            image.put("value", Base64.getEncoder().encodeToString(imageBytes));
            inputs.set("image", image);

            if (parameters != null) {
                parameters.forEach((k, v) -> {
                    if (v == null) return;
                    if (v instanceof Number n) {
                        inputs.put(k, n.doubleValue());
                    } else {
                        inputs.put(k, String.valueOf(v));
                    }
                });
            }

            root.set("inputs", inputs);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RoboflowWorkflowException("Failed to build Roboflow request body", e);
        }
    }

    /** Documented path first, then workspace-specific path from Roboflow UI. */
    private String[] workflowUrls(String workflowId) {
        String base = serverlessBase.replaceAll("/+$", "");
        String ws = workspace.trim().replaceAll("^/+|/+$", "");
        String wf = workflowId.trim().replaceAll("^/+|/+$", "");
        return new String[]{
                base + "/infer/workflows/" + ws + "/" + wf,
                base + "/" + ws + "/workflows/" + wf
        };
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(400L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String body) {
        if (body == null) return "";
        return body.length() > 240 ? body.substring(0, 240) + "…" : body;
    }
}
