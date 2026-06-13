package com.hobbietrades.backend.controller;

import com.hobbietrades.backend.service.scraping.PriceScrapeOrchestratorService;
import com.hobbietrades.backend.util.HobbyCategories;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/prices")
public class PriceScrapeController {

    private final PriceScrapeOrchestratorService scrapeOrchestrator;

    @Value("${hobbietrades.admin-key:}")
    private String adminKey;

    public PriceScrapeController(PriceScrapeOrchestratorService scrapeOrchestrator) {
        this.scrapeOrchestrator = scrapeOrchestrator;
    }

    /** Run all category keyword scrapes (admin only). */
    @PostMapping("/scrape-run")
    public ResponseEntity<Map<String, Object>> runScrape(
            @RequestParam(required = false) String key) {
        if (adminKey == null || adminKey.isBlank()) {
            return ResponseEntity.status(403).body(Map.of(
                    "success", false,
                    "message", "Full scrape is disabled until HOBBIETRADES_ADMIN_KEY is configured."
            ));
        }
        if (!adminKey.equals(key)) {
            return ResponseEntity.status(403).body(Map.of(
                    "success", false,
                    "message", "Invalid admin key"
            ));
        }
        return ResponseEntity.ok(scrapeOrchestrator.runFullScrape());
    }

    /** Scrape one keyword into price_reference (used from Create Listing). */
    @PostMapping("/scrape")
    public ResponseEntity<Map<String, Object>> scrapeOne(
            @RequestParam String keyword,
            @RequestParam String category,
            @RequestParam(required = false) String key) {
        if (keyword == null || keyword.isBlank() || keyword.length() > 80) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Keyword must be 1–80 characters."
            ));
        }
        if (!HobbyCategories.isAllowed(category)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Only Cameras and Instruments categories are supported."
            ));
        }
        if (adminKey != null && !adminKey.isBlank() && key != null && !adminKey.equals(key)) {
            return ResponseEntity.status(403).body(Map.of(
                    "success", false,
                    "message", "Invalid admin key"
            ));
        }
        return ResponseEntity.ok(scrapeOrchestrator.scrapeKeyword(keyword.trim(), category));
    }

    @GetMapping("/scrape-status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "referenceRows", scrapeOrchestrator.countReferences()
        ));
    }
}
