package com.hobbietrades.backend.controller;

import com.hobbietrades.backend.service.Esp32CaptureService;
import com.hobbietrades.backend.service.Esp32CaptureService.StoredCapture;
import com.hobbietrades.backend.service.ItemPhotoValidationService;
import com.hobbietrades.backend.service.ItemPhotoValidationService.ValidationResult;
import com.hobbietrades.backend.service.ItemValidationException;
import com.hobbietrades.backend.util.UploadValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * ESP32-CAM uploads a photo here; the create-listing page polls /latest to receive it.
 */
@RestController
@RequestMapping("/api/esp32")
public class Esp32CaptureController {

    private final Esp32CaptureService captureService;
    private final ItemPhotoValidationService photoValidationService;
    private final ImageUploadController imageUploadController;

    @Value("${hobbietrades.esp32.device-key:}")
    private String deviceKey;

    @Autowired
    public Esp32CaptureController(
            Esp32CaptureService captureService,
            ItemPhotoValidationService photoValidationService,
            @Lazy ImageUploadController imageUploadController) {
        this.captureService = captureService;
        this.photoValidationService = photoValidationService;
        this.imageUploadController = imageUploadController;
    }

    /**
     * ESP32 POSTs a JPEG here after taking a photo.
     * Requires ?key= matching ESP32_DEVICE_KEY on Render.
     */
    @PostMapping("/capture")
    public ResponseEntity<Map<String, Object>> capture(
            @RequestParam("photo") MultipartFile photo,
            @RequestParam("key") String key) {

        Map<String, Object> result = new HashMap<>();
        if (deviceKey == null || deviceKey.isBlank()) {
            result.put("success", false);
            result.put("message", "ESP32 device key not configured on server.");
            return ResponseEntity.status(503).body(result);
        }
        if (!deviceKey.equals(key)) {
            result.put("success", false);
            result.put("message", "Invalid device key.");
            return ResponseEntity.status(403).body(result);
        }

        try {
            UploadValidator.validateImage(photo);
            byte[] bytes = photo.getBytes();
            String mime = photo.getContentType() != null ? photo.getContentType() : "image/jpeg";

            ValidationResult validation = photoValidationService.validate(
                    bytes, imageUploadController::runHuggingFaceOnly);
            Map<String, Object> analysis = buildAnalyzeResponse(validation);

            captureService.store(bytes, mime, analysis);

            result.put("success", true);
            result.put("accepted", true);
            result.put("message", "Photo received — waiting for website to pick it up.");
            result.put("detectedCategory", analysis.get("detectedCategory"));
            result.put("confidence", analysis.get("confidence"));
            return ResponseEntity.ok(result);

        } catch (ItemValidationException | IllegalArgumentException e) {
            result.put("success", false);
            result.put("accepted", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("accepted", false);
            result.put("message", "Capture failed: " + e.getMessage());
            return ResponseEntity.status(500).body(result);
        }
    }

    /**
     * Create-listing page polls this after the user clicks "Use ESP32 Camera".
     * Pass since=timestamp (ms) from when the button was clicked.
     */
    @GetMapping("/latest")
    public ResponseEntity<Map<String, Object>> latest(@RequestParam("since") long since) {
        Map<String, Object> body = new HashMap<>();
        body.put("available", false);

        return captureService.takeIfNewerThan(since)
                .map(cap -> ResponseEntity.ok(toPollResponse(cap)))
                .orElseGet(() -> ResponseEntity.ok(body));
    }

    private Map<String, Object> toPollResponse(StoredCapture cap) {
        Map<String, Object> body = new HashMap<>(cap.analysis());
        body.put("available", true);
        body.put("capturedAt", cap.capturedAtMs());
        body.put("mimeType", cap.mime());
        body.put("imageBase64", Base64.getEncoder().encodeToString(cap.image()));
        return body;
    }

    private Map<String, Object> buildAnalyzeResponse(ValidationResult validation) {
        Map<String, String> ai = validation.analysis();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("accepted", true);
        result.put("detectedCategory", ai.get("category"));
        result.put("detectedCondition", ai.get("condition"));
        result.put("rawLabels", ai.get("rawLabels"));
        result.put("caption", ai.get("caption"));
        result.put("confidence", ai.get("confidence"));
        result.put("suggestedTitle", ai.get("suggestedTitle"));
        result.put("detectedBrand", ai.get("detectedBrand"));
        result.put("detectedModel", ai.get("detectedModel"));
        result.put("estimateKeyword", ai.get("estimateKeyword"));
        result.put("detectionSource", ai.getOrDefault("detectionSource", "roboflow"));
        result.put("message", "Photo accepted — " + ai.get("category") + " detected.");
        return result;
    }
}
