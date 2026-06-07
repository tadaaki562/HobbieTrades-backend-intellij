package com.hobbietrades.backend.service.scraping;

import org.jsoup.nodes.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Fallback international marketplace scrape (used when PH sites block datacenter IPs).
 */
@Component
@Order(4)
@ConditionalOnProperty(name = "hobbietrades.scrape.provider", havingValue = "legacy")
public class EbayPriceScraper implements MarketplacePriceScraper {

    private final ScrapingHttpClient httpClient;
    private final RobotsComplianceService robots;

    public EbayPriceScraper(ScrapingHttpClient httpClient, RobotsComplianceService robots) {
        this.httpClient = httpClient;
        this.robots = robots;
    }

    @Override
    public String sourceName() {
        return "eBay (search scrape)";
    }

    @Override
    public Optional<ScrapeSample> scrape(String keyword) {
        try {
            String q = URLEncoder.encode(keyword + " used", StandardCharsets.UTF_8);
            String url = "https://www.ebay.com/sch/i.html?_nkw=" + q + "&LH_ItemCondition=3000";
            Document doc = httpClient.fetchDocument(url, robots);
            List<Double> prices = HtmlPriceExtractor.extractPrices(doc.html());
            // eBay often shows USD — also match $123.45 and convert rough PHP x 56
            prices.addAll(extractUsdAsPhp(doc.html()));
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

    private List<Double> extractUsdAsPhp(String html) {
        List<Double> out = new java.util.ArrayList<>();
        var m = java.util.regex.Pattern.compile("\\$\\s*([\\d,]+(?:\\.\\d{2})?)").matcher(html);
        while (m.find()) {
            try {
                double usd = Double.parseDouble(m.group(1).replace(",", ""));
                double php = usd * 56.0;
                if (php >= 150 && php <= 500_000) {
                    out.add(php);
                }
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }
}
