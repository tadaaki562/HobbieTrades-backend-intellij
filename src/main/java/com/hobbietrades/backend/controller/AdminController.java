package com.hobbietrades.backend.controller;

import com.hobbietrades.backend.service.ListingMaintenanceService;
import com.hobbietrades.backend.service.ListingMaintenanceService.WipeResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ListingMaintenanceService listingMaintenance;

    @Value("${hobbietrades.admin-key:}")
    private String adminKey;

    public AdminController(ListingMaintenanceService listingMaintenance) {
        this.listingMaintenance = listingMaintenance;
    }

    /**
     * DELETE all browse listings, trades, messages, reviews, and stored photos.
     * Requires ?key= matching HOBBIETRADES_ADMIN_KEY on Render.
     */
    @PostMapping("/wipe-listings")
    public ResponseEntity<Map<String, Object>> wipeListings(@RequestParam(required = false) String key) {
        if (adminKey == null || adminKey.isBlank()) {
            return ResponseEntity.status(403).body(Map.of(
                    "success", false,
                    "message", "Admin key not configured. Set HOBBIETRADES_ADMIN_KEY on Render first."));
        }
        if (!adminKey.equals(key)) {
            return ResponseEntity.status(403).body(Map.of(
                    "success", false,
                    "message", "Invalid admin key."));
        }

        WipeResult result = listingMaintenance.wipeAllListings();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "All listings and related data removed.");
        body.put("itemsRemoved", result.itemsRemoved());
        body.put("tradesRemoved", result.tradesRemoved());
        body.put("messagesRemoved", result.messagesRemoved());
        body.put("reviewsRemoved", result.reviewsRemoved());
        body.put("galleryImagesRemoved", result.galleryImagesRemoved());
        body.put("diskFilesRemoved", result.diskFilesRemoved());
        return ResponseEntity.ok(body);
    }
}
