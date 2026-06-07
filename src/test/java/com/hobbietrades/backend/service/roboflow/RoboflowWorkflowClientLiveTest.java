package com.hobbietrades.backend.service.roboflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test against live Roboflow workflows. Skipped when ROBOFLOW_API_KEY is unset.
 */
class RoboflowWorkflowClientLiveTest {

    @Test
    void instrumentsWorkflowReturnsOutputs() throws Exception {
        String apiKey = System.getenv("ROBOFLOW_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(), "ROBOFLOW_API_KEY not set — skipping live test");

        RoboflowWorkflowClient client = new RoboflowWorkflowClient();
        ReflectionTestUtils.setField(client, "apiKey", apiKey);
        ReflectionTestUtils.setField(client, "workspace", "hobbietrades-dataset");
        ReflectionTestUtils.setField(client, "serverlessBase", "https://serverless.roboflow.com");
        ReflectionTestUtils.setField(client, "requestTimeoutSeconds", 60);

        byte[] imageBytes = minimalPngBytes();
        JsonNode response = client.runWorkflow("detect-and-classify-3", imageBytes, java.util.Map.of());

        assertNotNull(response);
        assertTrue(RoboflowWorkflowPredictionParser.hasPredictionOutputs(response),
                "Expected workflow output object with predictions");
    }

    private static byte[] minimalPngBytes() throws Exception {
        String b64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
        return Base64.getDecoder().decode(b64);
    }
}
