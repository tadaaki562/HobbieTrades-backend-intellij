package com.hobbietrades.backend.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Holds the latest ESP32 photo until the create-listing page picks it up. */
@Service
public class Esp32CaptureService {

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
    private final AtomicReference<Long> armedUntilMs = new AtomicReference<>(0L);

    /** Website calls this when the user clicks "Use ESP32 Camera". */
    public long armCaptureWindow(long durationMs) {
        long until = System.currentTimeMillis() + Math.max(durationMs, 5000L);
        armedUntilMs.set(until);
        return until;
    }

    /** ESP32 polls this; returns the active arm window id (until timestamp). */
    public long activeArmUntilMs() {
        long until = armedUntilMs.get();
        return until > System.currentTimeMillis() ? until : 0L;
    }

    public void store(byte[] image, String mime, Map<String, Object> analysis) {
        latest.set(new StoredCapture(image, mime, analysis, System.currentTimeMillis(), false));
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
