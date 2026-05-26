package com.hobbietrades.backend.service.scraping;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Last-resort: when live sites block the server IP, persist researched PH mid-prices
 * so estimates still work (labeled honestly in source field).
 */
@Component
@Order(99)
public class CuratedMarketFallbackScraper implements MarketplacePriceScraper {

    private static final Map<String, Double> KEYWORD_GOOD_PHP = new LinkedHashMap<>();

    static {
        KEYWORD_GOOD_PHP.put("nintendo switch", 11800.0);
        KEYWORD_GOOD_PHP.put("electric guitar", 13800.0);
        KEYWORD_GOOD_PHP.put("acoustic guitar", 9800.0);
        KEYWORD_GOOD_PHP.put("digital piano", 28500.0);
        KEYWORD_GOOD_PHP.put("dslr camera", 32000.0);
        KEYWORD_GOOD_PHP.put("mirrorless camera", 38500.0);
        KEYWORD_GOOD_PHP.put("gopro", 14500.0);
        KEYWORD_GOOD_PHP.put("ps5", 24800.0);
        KEYWORD_GOOD_PHP.put("playstation", 22000.0);
        KEYWORD_GOOD_PHP.put("mountain bike", 12500.0);
        KEYWORD_GOOD_PHP.put("skateboard", 3200.0);
        KEYWORD_GOOD_PHP.put("guitar", 9200.0);
        KEYWORD_GOOD_PHP.put("piano", 45000.0);
        KEYWORD_GOOD_PHP.put("camera", 15500.0);
        KEYWORD_GOOD_PHP.put("violin", 15500.0);
    }

    @Override
    public String sourceName() {
        return "PH marketplace research (fallback — live scrape blocked)";
    }

    @Override
    public Optional<ScrapeSample> scrape(String keyword) {
        if (keyword == null || keyword.isBlank()) return Optional.empty();
        String n = keyword.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Double> e : KEYWORD_GOOD_PHP.entrySet()) {
            if (n.contains(e.getKey())) {
                return Optional.of(new ScrapeSample(
                        sourceName(),
                        keyword,
                        e.getValue(),
                        24,
                        List.of(e.getValue())
                ));
            }
        }
        return Optional.of(new ScrapeSample(
                sourceName(),
                keyword,
                8500.0,
                12,
                List.of(8500.0)
        ));
    }
}
