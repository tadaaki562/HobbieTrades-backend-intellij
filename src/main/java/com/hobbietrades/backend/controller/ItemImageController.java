package com.hobbietrades.backend.controller;

import com.hobbietrades.backend.model.Item;
import com.hobbietrades.backend.model.ItemGalleryImage;
import com.hobbietrades.backend.repository.ItemGalleryImageRepository;
import com.hobbietrades.backend.repository.ItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Serves item photos from the database (persistent on Render) with disk fallback for legacy uploads.
 */
@RestController
@RequestMapping("/api/items")
public class ItemImageController {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ItemGalleryImageRepository galleryRepository;

    @Value("${upload.dir:uploads/}")
    private String uploadDir;

    @GetMapping("/{id}/photo")
    public ResponseEntity<byte[]> getMainPhoto(@PathVariable Long id) {
        Optional<Item> itemOpt = itemRepository.findById(id);
        if (itemOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Item item = itemOpt.get();

        if (item.getPhotoData() != null && item.getPhotoData().length > 0) {
            return bytesResponse(item.getPhotoData(), item.getPhotoMime());
        }

        return loadLegacyFile(item.getPhotoUrl());
    }

    @GetMapping("/{id}/gallery/{slot}")
    public ResponseEntity<byte[]> getGalleryPhoto(@PathVariable Long id, @PathVariable int slot) {
        Optional<ItemGalleryImage> img = galleryRepository.findByItemIdAndSlot(id, slot);
        if (img.isPresent()) {
            return bytesResponse(img.get().getImageData(), img.get().getMimeType());
        }
        return ResponseEntity.notFound().build();
    }

    private ResponseEntity<byte[]> bytesResponse(byte[] data, String mime) {
        MediaType type = MediaType.IMAGE_JPEG;
        if (mime != null && !mime.isBlank()) {
            try {
                type = MediaType.parseMediaType(mime);
            } catch (Exception ignored) {
                // default jpeg
            }
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .contentType(type)
                .body(data);
    }

    private ResponseEntity<byte[]> loadLegacyFile(String photoUrl) {
        if (photoUrl == null || photoUrl.isBlank() || !photoUrl.startsWith("/uploads/")) {
            return ResponseEntity.notFound().build();
        }
        try {
            String filename = photoUrl.substring("/uploads/".length());
            Path path = Paths.get(uploadDir).resolve(filename);
            if (!Files.exists(path)) {
                return ResponseEntity.notFound().build();
            }
            byte[] data = Files.readAllBytes(path);
            String mime = filename.endsWith(".png") ? "image/png"
                    : filename.endsWith(".webp") ? "image/webp" : "image/jpeg";
            return bytesResponse(data, mime);
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }
}
