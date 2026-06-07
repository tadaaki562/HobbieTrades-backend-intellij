package com.hobbietrades.backend.service.scraping;

import org.jsoup.nodes.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(1)
@ConditionalOnProperty(name = "hobbietrades.scrape.provider", havingValue = "legacy")
public class ShopeePhPriceScraper implements MarketplacePriceScraper {

    private static final Pattern SHOPEE_JSON_PRICE = Pattern.compile(
            "\"(?:price|price_min|price_before_discount)\"\\s*:\\s*(\\d{5,})");

    private final ScrapingHttpClient httpClient;
    private final RobotsComplianceService robots;

    public ShopeePhPriceScraper(ScrapingHttpClient httpClient, RobotsComplianceService robots) {
        this.httpClient = httpClient;
        this.robots = robots;
    }

    @Override
    public String sourceName() {
        return "Shopee PH (search scrape)";
    }

    @Override
    public Optional<ScrapeSample> scrape(String keyword) {
        List<String> urls = List.of(
                "https://shopee.ph/search?keyword=" + encode(keyword),
                "https://m.shopee.ph/search?keyword=" + encode(keyword)
        );
        for (String url : urls) {
            Optional<ScrapeSample> sample = tryUrl(url, keyword);
            if (sample.isPresent()) return sample;
        }
        return Optional.empty();
    }

    private Optional<ScrapeSample> tryUrl(String url, String keyword) {
        try {
            Document doc = httpClient.fetchDocument(url, robots);
            List<Double> prices = extractShopeePrices(doc.html());
            prices.addAll(HtmlPriceExtractor.extractPrices(doc.html()));
            if (prices.size() < 2) return Optional.empty();
            double median = HtmlPriceExtractor.median(prices);
            int cap = Math.min(prices.size(), 20);
            return Optional.of(new ScrapeSample(sourceName(), keyword, median, cap, prices.subList(0, cap)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static List<Double> extractShopeePrices(String html) {
        List<Double> prices = new ArrayList<>();
        Matcher m = SHOPEE_JSON_PRICE.matcher(html);
        while (m.find()) {
            try {
                long raw = Long.parseLong(m.group(1));
                double php = normalizeShopeePrice(raw);
                if (php >= 150 && php <= 500_000) {
                    prices.add(php);
                }
            } catch (NumberFormatException ignored) {}
        }
        return prices;
    }

    /** Shopee often stores price × 100000 (e.g. 1180000000 → ₱11,800). */
    static double normalizeShopeePrice(long raw) {
        if (raw > 1_000_000) {
            return raw / 100_000.0;
        }
        if (raw > 10_000) {
            return raw / 100.0;
        }
        return raw;
    }

    private static String encode(String keyword) {
        return URLEncoder.encode(keyword, StandardCharsets.UTF_8);
    }
}
