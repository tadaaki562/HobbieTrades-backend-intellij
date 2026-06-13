package com.hobbietrades.backend.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.*;

class UploadValidatorTest {

    @Test
    void rejectsNonImageExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "photo", "bad.exe", "application/octet-stream", new byte[]{1, 2, 3});
        assertThrows(IllegalArgumentException.class, () -> UploadValidator.validateImage(file));
    }

    @Test
    void acceptsJpeg() {
        MockMultipartFile file = new MockMultipartFile(
                "photo", "guitar.jpg", "image/jpeg", new byte[]{1, 2, 3});
        assertDoesNotThrow(() -> UploadValidator.validateImage(file));
        assertEquals(".jpg", UploadValidator.safeExtension(file));
    }
}
