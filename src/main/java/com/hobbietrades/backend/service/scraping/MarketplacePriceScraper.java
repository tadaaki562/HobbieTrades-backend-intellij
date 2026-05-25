package com.hobbietrades.backend.service.scraping;

import java.util.Optional;

public interface MarketplacePriceScraper {
    String sourceName();
    Optional<ScrapeSample> scrape(String keyword);
}
