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
            "Instruments", List.of("electric guitar", "acoustic guitar", "digital piano", "violin")
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
        Optional<ScrapeSample> sample = scrapeFirst(keyword);
        if (sample.isEmpty()) {
            out.put("success", false);
            out.put("keyword", keyword);
            out.put("category", category);
            out.put("message", "No prices extracted from Facebook public pages.");
            return out;
        }
        persistSample(sample.get(), category);
        ScrapeSample s = sample.get();
        boolean fallback = s.source().contains("fallback");
        out.put("success", true);
        out.put("keyword", keyword);
        out.put("category", category);
        out.put("source", s.source());
        out.put("medianPrice", s.medianPrice());
        out.put("message", fallback
                ? "Live sites blocked this server; saved PH research baseline prices instead."
                : "Saved prices from " + s.source());
        return out;
    }

    private Optional<ScrapeSample> scrapeFirst(String keyword) {
        for (MarketplacePriceScraper scraper : scrapers) {
            Optional<ScrapeSample> sample = scraper.scrape(keyword);
            if (sample.isPresent()) return sample;
        }
        return Optional.empty();
    }

    private boolean scrapeAndPersist(String keyword, String category) {
        Optional<ScrapeSample> sample = scrapeFirst(keyword);
        if (sample.isEmpty()) {
            return false;
        }
        persistSample(sample.get(), category);
        return true;
    }

    private void persistSample(ScrapeSample s, String category) {
        double goodBase = s.medianPrice();
        String keyword = s.keyword();

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
    }

    public long countReferences() {
        return priceReferenceRepository.count();
    }
}
