package com.hobbietrades.backend.config;

import com.hobbietrades.backend.model.PriceReference;
import com.hobbietrades.backend.repository.PriceReferenceRepository;
import com.hobbietrades.backend.service.scraping.PriceScrapeOrchestratorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Seeds baseline price_reference rows when DB is empty, then optionally kicks off
 * a background scrape (rate-limited) to refresh from marketplaces.
 */
@Component
public class PriceReferenceStartupSeeder implements ApplicationRunner {

    private final PriceReferenceRepository repository;
    private final PriceScrapeOrchestratorService scrapeOrchestrator;

    @Value("${hobbietrades.scrape.on-startup:false}")
    private boolean scrapeOnStartup;

    public PriceReferenceStartupSeeder(
            PriceReferenceRepository repository,
            PriceScrapeOrchestratorService scrapeOrchestrator) {
        this.repository = repository;
        this.scrapeOrchestrator = scrapeOrchestrator;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repository.count() == 0) {
            seedCuratedBaselines();
        }
        if (scrapeOnStartup) {
            Thread t = new Thread(() -> {
                try {
                    scrapeOrchestrator.runFullScrape();
                } catch (Exception ignored) {
                    // Scraping may fail on datacenter IPs; curated seed still works
                }
            }, "price-scrape-startup");
            t.setDaemon(true);
            t.start();
        }
    }

    private void seedCuratedBaselines() {
        record Row(String kw, String cat, String cond, double price) {}
        List<Row> rows = List.of(
                new Row("guitar", "Instruments", "Good", 9200),
                new Row("guitar", "Instruments", "Like New", 11800),
                new Row("electric guitar", "Instruments", "Good", 13800),
                new Row("digital piano", "Instruments", "Good", 28500),
                new Row("dslr camera", "Cameras", "Good", 32000),
                new Row("mirrorless camera", "Cameras", "Good", 38500),
                new Row("gopro", "Cameras", "Good", 14500),
                new Row("canon lens", "Cameras", "Good", 22000),
                new Row("acoustic guitar", "Instruments", "Good", 7800),
                new Row("bass guitar", "Instruments", "Good", 11200),
                new Row("violin", "Instruments", "Good", 6500),
                new Row("drum kit", "Instruments", "Good", 15800)
        );
        for (Row r : rows) {
            PriceReference ref = new PriceReference();
            ref.setKeyword(r.kw);
            ref.setCategory(r.cat);
            ref.setConditionLabel(r.cond);
            ref.setAvgPrice(BigDecimal.valueOf(r.price));
            ref.setSampleCount(24);
            ref.setSource("Curated PH marketplace baseline (pre-scrape seed)");
            ref.setScrapedAt(LocalDateTime.now());
            repository.save(ref);
        }
    }
}
