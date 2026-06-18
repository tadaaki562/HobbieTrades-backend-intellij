package com.hobbietrades.backend.util;

import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Set;

/** Validates uploaded image files (extension + content type). */
public final class UploadValidator {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".jpg", ".jpeg", ".png", ".webp", ".gif");
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/gif"
    );

    private UploadValidator() {}

    public static void validateImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Photo file is required.");
        }
        String ext = extensionOf(file.getOriginalFilename());
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("Only JPG, PNG, WEBP, or GIF images are allowed.");
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()) {
            String ct = contentType.toLowerCase(Locale.ROOT);
            if (!ALLOWED_CONTENT_TYPES.contains(ct) && !"application/octet-stream".equals(ct)) {
                throw new IllegalArgumentException("Invalid image type: " + contentType);
            }
        }
    }

    /** Safe extension for saved files — never trust client filename alone. */
    public static String safeExtension(MultipartFile file) {
        String ext = extensionOf(file.getOriginalFilename());
        if (ALLOWED_EXTENSIONS.contains(ext)) {
            return ".jpg".equals(ext) || ".jpeg".equals(ext) ? ".jpg" : ext;
        }
        String contentType = file.getContentType();
        if (contentType != null) {
            return switch (contentType.toLowerCase(Locale.ROOT)) {
                case "image/png" -> ".png";
                case "image/webp" -> ".webp";
                case "image/gif" -> ".gif";
                default -> ".jpg";
            };
        }
        return ".jpg";
    }

    private static String extensionOf(String filename) {
        if (filename == null || !filename.contains(".")) {
            return ".jpg";
        }
        return filename.substring(filename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
    }
}
