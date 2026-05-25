package com.hobbietrades.backend.service.scraping;

import com.hobbietrades.backend.model.PriceReference;
import com.hobbietrades.backend.repository.PriceReferenceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class PriceScrapeOrchestratorService {

    private static final List<String> CONDITIONS = List.of("Like New", "Good", "Fair", "Worn");

    private static final Map<String, List<String>> CATEGORY_KEYWORDS = Map.of(
            "Cameras", List.of("dslr camera", "mirrorless camera", "canon camera", "gopro"),
            "Instruments", List.of("acoustic guitar", "electric guitar", "digital piano", "violin"),
            "Gaming", List.of("nintendo switch", "ps5 console", "xbox controller", "gaming headset"),
            "Sports", List.of("mountain bike", "skateboard", "tennis racket", "basketball"),
            "Art", List.of("acrylic paint set", "canvas easel", "paint brush set"),
            "Craft", List.of("sewing machine", "crochet yarn", "knitting kit"),
            "Other", List.of("hobby kit", "collectible figure", "trading cards")
    );

    private static final Map<String, Double> CONDITION_FACTOR = Map.of(
            "Like New", 0.80,
            "Good", 0.65,
            "Fair", 0.50,
            "Worn", 0.30
    );

    private final List<MarketplacePriceScraper> scrapers;
    private final PriceReferenceRepository priceReferenceRepository;

    @Value("${hobbietrades.scrape.enabled:true}")
    private boolean scrapeEnabled;

    @Value("${hobbietrades.scrape.max-keywords-per-run:12}")
    private int maxKeywordsPerRun;

    public PriceScrapeOrchestratorService(
            List<MarketplacePriceScraper> scrapers,
            PriceReferenceRepository priceReferenceRepository) {
        this.scrapers = scrapers;
        this.priceReferenceRepository = priceReferenceRepository;
    }

    public Map<String, Object> runFullScrape() {
        Map<String, Object> report = new LinkedHashMap<>();
        if (!scrapeEnabled) {
            report.put("success", false);
            report.put("message", "Scraping disabled (hobbietrades.scrape.enabled=false)");
            return report;
        }

        int saved = 0;
        int attempted = 0;
        List<String> errors = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : CATEGORY_KEYWORDS.entrySet()) {
            String category = entry.getKey();
            int count = 0;
            for (String keyword : entry.getValue()) {
                if (count >= maxKeywordsPerRun) break;
                attempted++;
                try {
                    boolean ok = scrapeAndPersist(keyword, category);
                    if (ok) saved += CONDITIONS.size();
                    count++;
                } catch (Exception e) {
                    errors.add(keyword + ": " + e.getMessage());
                }
            }
        }

        report.put("success", true);
        report.put("attemptedKeywords", attempted);
        report.put("rowsUpserted", saved);
        report.put("errors", errors);
        report.put("finishedAt", LocalDateTime.now().toString());
        return report;
    }

    public Map<String, Object> scrapeKeyword(String keyword, String category) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!scrapeEnabled) {
            out.put("success", false);
            out.put("message", "Scraping disabled");
            return out;
        }
        boolean ok = scrapeAndPersist(keyword, category);
        out.put("success", ok);
        out.put("keyword", keyword);
        out.put("category", category);
        out.put("message", ok ? "Saved scraped averages to price_reference" : "No prices extracted (site may have blocked request)");
        return out;
    }

    private boolean scrapeAndPersist(String keyword, String category) {
        Optional<ScrapeSample> sample = Optional.empty();
        for (MarketplacePriceScraper scraper : scrapers) {
            sample = scraper.scrape(keyword);
            if (sample.isPresent()) break;
        }
        if (sample.isEmpty()) {
            return false;
        }

        ScrapeSample s = sample.get();
        double goodBase = s.medianPrice();

        for (String condition : CONDITIONS) {
            double factor = CONDITION_FACTOR.getOrDefault(condition, 0.65);
            BigDecimal avg = BigDecimal.valueOf(goodBase * factor).setScale(2, RoundingMode.HALF_UP);

            PriceReference ref = priceReferenceRepository
                    .findFirstByKeywordIgnoreCaseAndCategoryAndConditionLabel(keyword, category, condition)
                    .orElse(new PriceReference());

            ref.setKeyword(keyword);
            ref.setCategory(category);
            ref.setConditionLabel(condition);
            ref.setAvgPrice(avg);
            ref.setSampleCount(s.sampleCount());
            ref.setSource(s.source());
            ref.setScrapedAt(LocalDateTime.now());
            priceReferenceRepository.save(ref);
        }
        return true;
    }

    public long countReferences() {
        return priceReferenceRepository.count();
    }
}
