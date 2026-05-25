package com.hobbietrades.backend.service.scraping;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HtmlPriceExtractor {

    private static final Pattern PESO = Pattern.compile("₱\\s*([\\d,]+(?:\\.\\d{2})?)");
    private static final Pattern JSON_PRICE = Pattern.compile("\"price\"\\s*:\\s*([\\d.]+)");
    private static final Pattern DATA_PRICE = Pattern.compile("data-price=\"(\\d+)\"");

    private HtmlPriceExtractor() {}

    public static List<Double> extractPrices(String html) {
        List<Double> prices = new ArrayList<>();
        if (html == null || html.isBlank()) return prices;

        for (Pattern p : List.of(PESO, JSON_PRICE, DATA_PRICE)) {
            Matcher m = p.matcher(html);
            while (m.find()) {
                double v = parseAmount(m.group(1));
                if (v >= 150 && v <= 500_000) {
                    prices.add(v);
                }
            }
        }
        return prices;
    }

    public static double median(List<Double> values) {
        if (values == null || values.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static double parseAmount(String raw) {
        String cleaned = raw.replace(",", "").trim();
        return Double.parseDouble(cleaned);
    }

    public static String slugKeyword(String keyword) {
        return keyword.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", "-");
    }
}
