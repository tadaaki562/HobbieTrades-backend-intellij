package com.hobbietrades.backend.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImageStorageHelperTest {

    @Test
    void normalizedMime_defaultsToJpeg() {
        assertEquals("image/jpeg", ImageStorageHelper.normalizedMime(null));
        assertEquals("image/jpeg", ImageStorageHelper.normalizedMime(""));
    }

    @Test
    void prepareForDatabase_returnsEmptyUnchanged() {
        assertNull(ImageStorageHelper.prepareForDatabase(null, "image/jpeg"));
        assertArrayEquals(new byte[0], ImageStorageHelper.prepareForDatabase(new byte[0], "image/jpeg"));
    }

    @Test
    void prepareForDatabase_keepsSmallJpeg() {
        byte[] tiny = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
        assertSame(tiny, ImageStorageHelper.prepareForDatabase(tiny, "image/jpeg"));
    }
}
