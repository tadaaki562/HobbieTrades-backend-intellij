package com.hobbietrades.backend.controller;

import com.hobbietrades.backend.service.scraping.PriceScrapeOrchestratorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/prices")
@CrossOrigin(origins = "*")
public class PriceScrapeController {

    private final PriceScrapeOrchestratorService scrapeOrchestrator;

    @Value("${hobbietrades.admin-key:}")
    private String adminKey;

    public PriceScrapeController(PriceScrapeOrchestratorService scrapeOrchestrator) {
        this.scrapeOrchestrator = scrapeOrchestrator;
    }

    /** Run all category keyword scrapes (rate-limited). Optional ?key= for admin guard. */
    @PostMapping("/scrape-run")
    public ResponseEntity<Map<String, Object>> runScrape(
            @RequestParam(required = false) String key) {
        if (adminKey != null && !adminKey.isBlank() && !adminKey.equals(key)) {
            return ResponseEntity.status(403).body(Map.of(
                    "success", false,
                    "message", "Invalid admin key"
            ));
        }
        return ResponseEntity.ok(scrapeOrchestrator.runFullScrape());
    }

    /** Scrape one keyword into price_reference (Good/Fair/Worn/Like New rows). */
    @PostMapping("/scrape")
    public ResponseEntity<Map<String, Object>> scrapeOne(
            @RequestParam String keyword,
            @RequestParam String category,
            @RequestParam(required = false) String key) {
        if (adminKey != null && !adminKey.isBlank() && !adminKey.equals(key)) {
            return ResponseEntity.status(403).body(Map.of(
                    "success", false,
                    "message", "Invalid admin key"
            ));
        }
        return ResponseEntity.ok(scrapeOrchestrator.scrapeKeyword(keyword, category));
    }

    @GetMapping("/scrape-status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "referenceRows", scrapeOrchestrator.countReferences()
        ));
    }
}
