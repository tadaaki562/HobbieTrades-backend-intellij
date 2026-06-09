package com.hobbietrades.backend.service.roboflow;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RoboflowDetectionModelClientTest {

    @Test
    void multipartBodyIncludesFileField() {
        byte[] image = new byte[]{0x01, 0x02, 0x03};
        byte[] body = RoboflowDetectionModelClient.buildMultipartFileBody("test-boundary", image);
        String text = new String(body, StandardCharsets.UTF_8);

        assertTrue(text.startsWith("--test-boundary\r\n"));
        assertTrue(text.contains("name=\"file\""));
        assertTrue(text.contains("filename=\"image.jpg\""));
        assertTrue(text.contains("Content-Type: image/jpeg"));
        assertTrue(text.endsWith("--test-boundary--\r\n"));
    }
}
