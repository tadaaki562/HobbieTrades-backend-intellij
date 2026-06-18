package com.hobbietrades.backend.controller;

import com.hobbietrades.backend.service.Esp32CaptureService;
import com.hobbietrades.backend.service.Esp32CaptureService.PreviewFrame;
import com.hobbietrades.backend.service.Esp32CaptureService.PreviewState;
import com.hobbietrades.backend.service.Esp32CaptureService.StoredCapture;
import com.hobbietrades.backend.service.ItemPhotoValidationService;
import com.hobbietrades.backend.service.ItemPhotoValidationService.ValidationResult;
import com.hobbietrades.backend.service.ItemValidationException;
import com.hobbietrades.backend.util.AnalyzeResponseHelper;
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
 * ESP32-CAM live preview + manual capture for create-listing.
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

    /** Website opens the ESP32 camera modal. */
    @PostMapping("/preview/start")
    public ResponseEntity<Map<String, Object>> startPreview() {
        Map<String, Object> body = new HashMap<>();
        PreviewState preview = captureService.startPreview(120_000L);
        body.put("success", true);
        body.put("session", preview.sessionId());
        body.put("until", preview.untilMs());
        body.put("message", "Live preview started.");
        return ResponseEntity.ok(body);
    }

    /** Website closes the ESP32 camera modal. */
    @PostMapping("/preview/stop")
    public ResponseEntity<Map<String, Object>> stopPreview() {
        captureService.stopPreview();
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        return ResponseEntity.ok(body);
    }

    /** Website polls this to render the live camera feed. */
    @GetMapping("/preview/frame")
    public ResponseEntity<Map<String, Object>> previewFrame(@RequestParam("since") long since) {
        Map<String, Object> body = new HashMap<>();
        body.put("available", false);

        return captureService.previewFrameNewerThan(since)
                .map(frame -> {
                    body.put("available", true);
                    body.put("updatedAt", frame.updatedAtMs());
                    body.put("mimeType", "image/jpeg");
                    body.put("imageBase64", Base64.getEncoder().encodeToString(frame.image()));
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.ok(body));
    }

    /** Website calls this when the user clicks Take Photo. */
    @PostMapping("/capture/request")
    public ResponseEntity<Map<String, Object>> requestCapture() {
        Map<String, Object> body = new HashMap<>();
        long session = captureService.requestCapture();
        if (session <= 0) {
            body.put("success", false);
            body.put("message", "Live preview is not active.");
            return ResponseEntity.badRequest().body(body);
        }
        body.put("success", true);
        body.put("session", session);
        body.put("message", "Capture requested.");
        return ResponseEntity.ok(body);
    }

    /**
     * ESP32 polls this. When preview is active it should stream frames.
     * When captureSession matches, it should take the final photo.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@RequestParam("key") String key) {
        Map<String, Object> body = new HashMap<>();
        if (deviceKey == null || deviceKey.isBlank()) {
            body.put("preview", false);
            body.put("message", "ESP32 device key not configured on server.");
            return ResponseEntity.status(503).body(body);
        }
        if (!deviceKey.equals(key)) {
            body.put("preview", false);
            body.put("message", "Invalid device key.");
            return ResponseEntity.status(403).body(body);
        }

        return captureService.activePreview()
                .map(preview -> {
                    body.put("preview", true);
                    body.put("session", preview.sessionId());
                    body.put("captureSession", captureService.pendingCaptureSession());
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> {
                    body.put("preview", false);
                    body.put("session", 0);
                    body.put("captureSession", 0);
                    return ResponseEntity.ok(body);
                });
    }

    /** ESP32 uploads low-res preview frames while preview mode is active. */
    @PostMapping("/preview")
    public ResponseEntity<Map<String, Object>> previewUpload(
            @RequestParam("photo") MultipartFile photo,
            @RequestParam("key") String key) {

        Map<String, Object> result = new HashMap<>();
        if (!isDeviceAuthorized(key, result)) {
            return unauthorized(result);
        }
        if (captureService.activePreview().isEmpty()) {
            result.put("success", false);
            result.put("message", "Preview is not active.");
            return ResponseEntity.badRequest().body(result);
        }

        try {
            UploadValidator.validateImage(photo);
            captureService.storePreviewFrame(photo.getBytes());
            result.put("success", true);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "Preview upload failed.");
            return ResponseEntity.status(500).body(result);
        }
    }

    /** ESP32 POSTs the final JPEG after the user clicks Take Photo on the website. */
    @PostMapping("/capture")
    public ResponseEntity<Map<String, Object>> capture(
            @RequestParam("photo") MultipartFile photo,
            @RequestParam("key") String key) {

        Map<String, Object> result = new HashMap<>();
        if (!isDeviceAuthorized(key, result)) {
            return unauthorized(result);
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

    /** Create-listing page polls this after Take Photo is clicked. */
    @GetMapping("/latest")
    public ResponseEntity<Map<String, Object>> latest(@RequestParam("since") long since) {
        Map<String, Object> body = new HashMap<>();
        body.put("available", false);

        return captureService.takeIfNewerThan(since)
                .map(cap -> ResponseEntity.ok(toPollResponse(cap)))
                .orElseGet(() -> ResponseEntity.ok(body));
    }

    private boolean isDeviceAuthorized(String key, Map<String, Object> result) {
        if (deviceKey == null || deviceKey.isBlank()) {
            result.put("success", false);
            result.put("message", "ESP32 device key not configured on server.");
            return false;
        }
        if (!deviceKey.equals(key)) {
            result.put("success", false);
            result.put("message", "Invalid device key.");
            return false;
        }
        return true;
    }

    private ResponseEntity<Map<String, Object>> unauthorized(Map<String, Object> result) {
        String message = String.valueOf(result.getOrDefault("message", "Unauthorized"));
        if (message.contains("not configured")) {
            return ResponseEntity.status(503).body(result);
        }
        return ResponseEntity.status(403).body(result);
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
        AnalyzeResponseHelper.putAnalysisFields(result, ai);
        result.put("message", "Photo accepted — " + ai.get("category") + " detected.");
        return result;
    }
}
