package com.hobbietrades.backend.service.roboflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoboflowWorkflowPredictionParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesPredictionsFromWorkflowFixture() throws Exception {
        JsonNode root = mapper.readTree(new ClassPathResource("roboflow/workflow-sample-response.json").getInputStream());

        assertTrue(RoboflowWorkflowPredictionParser.hasPredictionOutputs(root));

        List<RoboflowWorkflowPredictionParser.ParsedPrediction> preds =
                RoboflowWorkflowPredictionParser.extractPredictions(root);

        assertFalse(preds.isEmpty());
        assertEquals("electric-guitar", preds.get(0).className());
        assertTrue(preds.get(0).confidence() > 0.85);
    }
}
