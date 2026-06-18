package com.hobbietrades.backend.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Holds ESP32 preview frames and the latest capture for create-listing. */
@Service
public class Esp32CaptureService {

    public record PreviewState(long sessionId, long untilMs) {}

    public record PreviewFrame(byte[] image, long updatedAtMs) {}

    public record StoredCapture(
            byte[] image,
            String mime,
            Map<String, Object> analysis,
            long capturedAtMs,
            boolean claimed) {

        StoredCapture markClaimed() {
            return new StoredCapture(image, mime, analysis, capturedAtMs, true);
        }
    }

    private final AtomicReference<StoredCapture> latest = new AtomicReference<>();
    private final AtomicReference<Long> previewUntilMs = new AtomicReference<>(0L);
    private final AtomicLong previewSessionId = new AtomicLong(0L);
    private final AtomicLong pendingCaptureSessionId = new AtomicLong(0L);
    private final AtomicReference<PreviewFrame> previewFrame = new AtomicReference<>();
    private final AtomicLong lastPreviewFrameMs = new AtomicLong(0L);
    private final AtomicLong lastHeartbeatMs = new AtomicLong(0L);

    public void recordHeartbeat() {
        lastHeartbeatMs.set(System.currentTimeMillis());
    }

    public long lastPreviewFrameMs() {
        return lastPreviewFrameMs.get();
    }

    public long lastHeartbeatMs() {
        return lastHeartbeatMs.get();
    }

    /** Website opens the live camera modal. */
    public PreviewState startPreview(long durationMs) {
        long until = System.currentTimeMillis() + Math.max(durationMs, 10_000L);
        long sessionId = previewSessionId.incrementAndGet();
        previewUntilMs.set(until);
        pendingCaptureSessionId.set(0);
        previewFrame.set(null);
        return new PreviewState(sessionId, until);
    }

    public void stopPreview() {
        previewUntilMs.set(0L);
        pendingCaptureSessionId.set(0);
        previewFrame.set(null);
    }

    public Optional<PreviewState> activePreview() {
        long until = previewUntilMs.get();
        if (until <= System.currentTimeMillis()) {
            return Optional.empty();
        }
        long sessionId = previewSessionId.get();
        return sessionId > 0 ? Optional.of(new PreviewState(sessionId, until)) : Optional.empty();
    }

    /** User clicked Take Photo while preview is open. */
    public long requestCapture() {
        Optional<PreviewState> preview = activePreview();
        if (preview.isEmpty()) {
            return 0L;
        }
        long sessionId = preview.get().sessionId();
        pendingCaptureSessionId.set(sessionId);
        return sessionId;
    }

    public long pendingCaptureSession() {
        if (activePreview().isEmpty()) {
            return 0L;
        }
        return pendingCaptureSessionId.get();
    }

    public void clearCaptureRequest() {
        pendingCaptureSessionId.set(0);
    }

    public void storePreviewFrame(byte[] image) {
        long now = System.currentTimeMillis();
        previewFrame.set(new PreviewFrame(image, now));
        lastPreviewFrameMs.set(now);
        recordHeartbeat();
    }

    public Optional<PreviewFrame> previewFrameNewerThan(long sinceMs) {
        PreviewFrame frame = previewFrame.get();
        if (frame == null || frame.updatedAtMs() <= sinceMs) {
            return Optional.empty();
        }
        return Optional.of(frame);
    }

    public void store(byte[] image, String mime, Map<String, Object> analysis) {
        latest.set(new StoredCapture(image, mime, analysis, System.currentTimeMillis(), false));
        clearCaptureRequest();
    }

    /** Returns the capture once if it is newer than {@code sinceMs} and not yet consumed. */
    public Optional<StoredCapture> takeIfNewerThan(long sinceMs) {
        StoredCapture current = latest.get();
        if (current == null || current.claimed() || current.capturedAtMs() <= sinceMs) {
            return Optional.empty();
        }
        if (latest.compareAndSet(current, current.markClaimed())) {
            return Optional.of(current);
        }
        return Optional.empty();
    }
}
