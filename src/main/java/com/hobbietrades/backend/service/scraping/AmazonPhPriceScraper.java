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
@Order(2)
@ConditionalOnProperty(name = "hobbietrades.scrape.provider", havingValue = "legacy")
public class AmazonPhPriceScraper implements MarketplacePriceScraper {

    private static final Pattern A_PRICE_WHOLE = Pattern.compile(
            "class=\"a-price-whole\">([\\d,]+)(?:<[^>]+>)*<[^>]+>class=\"a-price-fraction\">(\\d{2})?");
    private static final Pattern A_OFFSCREEN = Pattern.compile(
            "class=\"a-offscreen\">\\s*₱?\\s*([\\d,]+(?:\\.\\d{2})?)");

    private final ScrapingHttpClient httpClient;
    private final RobotsComplianceService robots;

    public AmazonPhPriceScraper(ScrapingHttpClient httpClient, RobotsComplianceService robots) {
        this.httpClient = httpClient;
        this.robots = robots;
    }

    @Override
    public String sourceName() {
        return "Amazon PH (search scrape)";
    }

    @Override
    public Optional<ScrapeSample> scrape(String keyword) {
        try {
            String url = "https://www.amazon.com.ph/s?k=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
            Document doc = httpClient.fetchDocument(url, robots);
            List<Double> prices = extractAmazonPrices(doc.html());
            prices.addAll(HtmlPriceExtractor.extractPrices(doc.html()));
            if (prices.size() < 2) return Optional.empty();
            double median = HtmlPriceExtractor.median(prices);
            int cap = Math.min(prices.size(), 20);
            return Optional.of(new ScrapeSample(sourceName(), keyword, median, cap, prices.subList(0, cap)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    static List<Double> extractAmazonPrices(String html) {
        List<Double> prices = new ArrayList<>();
        Matcher m = A_OFFSCREEN.matcher(html);
        while (m.find()) {
            addPrice(prices, m.group(1));
        }
        m = A_PRICE_WHOLE.matcher(html);
        while (m.find()) {
            String whole = m.group(1).replace(",", "");
            String frac = m.group(2) != null ? m.group(2) : "00";
            addPrice(prices, whole + "." + frac);
        }
        return prices;
    }

    private static void addPrice(List<Double> prices, String raw) {
        try {
            double v = Double.parseDouble(raw.replace(",", "").trim());
            if (v >= 150 && v <= 500_000) prices.add(v);
        } catch (NumberFormatException ignored) {}
    }
}
