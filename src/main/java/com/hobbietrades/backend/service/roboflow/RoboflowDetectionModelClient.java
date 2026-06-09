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
import java.util.List;
import java.util.UUID;

/**
 * Direct object-detection model calls (bypasses broken workflow steps).
 * Uses Serverless v2 {@code POST /infer/{workspace}/{project}/{version}} with multipart file upload.
 *
 * @see <a href="https://docs.roboflow.com/developer/rest-api/run-a-model-on-an-image">Roboflow Serverless v2</a>
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
        String boundary = "----RoboflowBoundary" + UUID.randomUUID();
        byte[] body = buildMultipartFileBody(boundary, imageBytes);
        RoboflowWorkflowException last = null;

        for (String url : inferUrls(slug, version)) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(45))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                if (code >= 200 && code < 300) {
                    return parseModelResponse(response.body());
                }
                last = new RoboflowWorkflowException(
                        "Detection model HTTP " + code + " at " + redactApiKey(url), code);
                if (code == 404 || code == 405) {
                    continue;
                }
                break;
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
                base + "/" + ws + "/" + slug + "/" + version + "?api_key=" + key,
                "https://detect.roboflow.com/" + slug + "/" + version + "?api_key=" + key
        };
    }

    static byte[] buildMultipartFileBody(String boundary, byte[] imageBytes) {
        String crlf = "\r\n";
        String header = "--" + boundary + crlf
                + "Content-Disposition: form-data; name=\"file\"; filename=\"image.jpg\"" + crlf
                + "Content-Type: image/jpeg" + crlf + crlf;
        String footer = crlf + "--" + boundary + "--" + crlf;

        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] footerBytes = footer.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[headerBytes.length + imageBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
        System.arraycopy(imageBytes, 0, body, headerBytes.length, imageBytes.length);
        System.arraycopy(footerBytes, 0, body, headerBytes.length + imageBytes.length, footerBytes.length);
        return body;
    }

    private static String redactApiKey(String url) {
        return url.replaceAll("api_key=[^&]+", "api_key=***");
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
