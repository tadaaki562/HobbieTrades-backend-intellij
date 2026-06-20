package com.hobbietrades.backend.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DetectionCategoryArbiterTest {

    @Test
    void violinBeatsGenericMirrorlessFalsePositive() {
        List<DetectionCategoryArbiter.Hit> hits = List.of(
                new DetectionCategoryArbiter.Hit("mirrorless camera", 0.92, "Cameras"),
                new DetectionCategoryArbiter.Hit("violin", 0.82, "Instruments")
        );
        Map<String, DetectionCategoryArbiter.Hit> best = DetectionCategoryArbiter.bestPerCategory(hits);
        DetectionCategoryArbiter.Hit winner = DetectionCategoryArbiter.resolveWinner(best, hits);

        assertEquals("Instruments", winner.category());
        assertEquals("violin", winner.className());
        assertFalse(DetectionCategoryArbiter.isAmbiguous(best, winner));
    }

    @Test
    void clearCameraBeatsWeakInstrumentFalsePositive() {
        List<DetectionCategoryArbiter.Hit> hits = List.of(
                new DetectionCategoryArbiter.Hit("Canon EOS R6", 0.88, "Cameras"),
                new DetectionCategoryArbiter.Hit("guitar", 0.71, "Instruments")
        );
        Map<String, DetectionCategoryArbiter.Hit> best = DetectionCategoryArbiter.bestPerCategory(hits);
        DetectionCategoryArbiter.Hit winner = DetectionCategoryArbiter.resolveWinner(best, hits);

        assertEquals("Cameras", winner.category());
        assertFalse(DetectionCategoryArbiter.isAmbiguous(best, winner));
    }

    @Test
    void closeScoresStayAmbiguous() {
        List<DetectionCategoryArbiter.Hit> hits = List.of(
                new DetectionCategoryArbiter.Hit("dslr camera", 0.78, "Cameras"),
                new DetectionCategoryArbiter.Hit("digital piano", 0.74, "Instruments")
        );
        Map<String, DetectionCategoryArbiter.Hit> best = DetectionCategoryArbiter.bestPerCategory(hits);
        DetectionCategoryArbiter.Hit winner = DetectionCategoryArbiter.resolveWinner(best, hits);

        assertTrue(DetectionCategoryArbiter.isAmbiguous(best, winner));
    }

    @Test
    void definiteInstrumentTokensRecognized() {
        assertTrue(DetectionCategoryArbiter.isDefiniteInstrument("electric-guitar"));
        assertTrue(DetectionCategoryArbiter.isGenericCameraLabel("mirrorless camera"));
        assertFalse(DetectionCategoryArbiter.isGenericCameraLabel("Canon lens"));
    }
}
