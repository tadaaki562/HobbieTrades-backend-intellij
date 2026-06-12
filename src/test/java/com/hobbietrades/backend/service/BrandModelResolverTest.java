package com.hobbietrades.backend.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BrandModelResolverTest {

    @Test
    void roboflowElectricGuitarLabelProducesElectricGuitarTitle() {
        BrandModelResolver.Hint hint = BrandModelResolver.resolve(
                List.of(new BrandModelResolver.LabelInput("electric-guitar", 0.88)),
                "Instruments");

        assertEquals("Electric Guitar", hint.title());
        assertEquals("Electric Guitar", hint.model());
    }

    @Test
    void personLabelIgnoredWhenGuitarAlsoDetected() {
        BrandModelResolver.Hint hint = BrandModelResolver.resolve(
                List.of(
                        new BrandModelResolver.LabelInput("person", 0.95),
                        new BrandModelResolver.LabelInput("electric-guitar", 0.82)),
                "Instruments");

        assertEquals("Electric Guitar", hint.title());
        assertFalse(BrandModelResolver.isGenericCategoryTitle(hint.title(), "Instruments"));
    }

    @Test
    void genericCategoryTitleDetected() {
        assertTrue(BrandModelResolver.isGenericCategoryTitle("Instruments Item", "Instruments"));
        assertFalse(BrandModelResolver.isGenericCategoryTitle("Electric Guitar", "Instruments"));
    }
}
