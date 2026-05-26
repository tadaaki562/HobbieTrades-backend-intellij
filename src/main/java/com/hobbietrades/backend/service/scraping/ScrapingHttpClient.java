package com.hobbietrades.backend.service.scraping;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class ScrapingHttpClient {

    /** Browser-like UA reduces blocks on Shopee/Lazada/Amazon vs obvious bot strings. */
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/122.0.0.0 Safari/537.36 HobbieTradesResearch/1.0";

    @Value("${hobbietrades.scrape.delay-ms:2500}")
    private long delayMs;

    private final AtomicLong lastRequestAt = new AtomicLong(0);

    public Document fetchDocument(String url, RobotsComplianceService robots) throws IOException {
        URI uri = URI.create(url);
        if (!robots.mayFetch(uri)) {
            throw new IOException("robots.txt disallows fetch for host: " + uri.getHost());
        }
        throttle();
        Connection conn = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(20000)
                .followRedirects(true)
                .ignoreHttpErrors(false)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-PH,en;q=0.9")
                .header("Cache-Control", "no-cache")
                .maxBodySize(2_000_000);

        Connection.Response response = conn.execute();
        int code = response.statusCode();
        if (code == 429 || code == 503) {
            throw new IOException("Rate limited by host (HTTP " + code + ")");
        }
        if (code >= 400) {
            throw new IOException("HTTP " + code + " for " + url);
        }
        return response.parse();
    }

    private void throttle() {
        long now = System.currentTimeMillis();
        long prev = lastRequestAt.get();
        long wait = delayMs - (now - prev);
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastRequestAt.set(System.currentTimeMillis());
    }
}
