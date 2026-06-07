package com.hobbietrades.backend.service.scraping;

import org.jsoup.nodes.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

@Component
@Order(3)
@ConditionalOnProperty(name = "hobbietrades.scrape.provider", havingValue = "legacy")
public class LazadaPhPriceScraper implements MarketplacePriceScraper {

    private final ScrapingHttpClient httpClient;
    private final RobotsComplianceService robots;

    public LazadaPhPriceScraper(ScrapingHttpClient httpClient, RobotsComplianceService robots) {
        this.httpClient = httpClient;
        this.robots = robots;
    }

    @Override
    public String sourceName() {
        return "Lazada PH (search scrape)";
    }

    @Override
    public Optional<ScrapeSample> scrape(String keyword) {
        try {
            String q = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            String url = "https://www.lazada.com.ph/catalog/?q=" + q;
            Document doc = httpClient.fetchDocument(url, robots);
            List<Double> prices = HtmlPriceExtractor.extractPrices(doc.html());
            if (prices.size() < 2) {
                return Optional.empty();
            }
            double median = HtmlPriceExtractor.median(prices);
            int cap = Math.min(prices.size(), 20);
            return Optional.of(new ScrapeSample(
                    sourceName(),
                    keyword,
                    median,
                    cap,
                    prices.subList(0, cap)
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
