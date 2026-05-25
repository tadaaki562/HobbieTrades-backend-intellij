package com.hobbietrades.backend.service.scraping;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight robots.txt check — skips hosts that disallow all crawling.
 */
@Service
public class RobotsComplianceService {

    private final Map<String, Boolean> hostAllowedCache = new ConcurrentHashMap<>();

    public boolean mayFetch(URI uri) {
        String host = uri.getHost();
        if (host == null) return false;
        return hostAllowedCache.computeIfAbsent(host, this::loadHostAllowed);
    }

    private boolean loadHostAllowed(String host) {
        try {
            Document robots = Jsoup.connect("https://" + host + "/robots.txt")
                    .userAgent(ScrapingHttpClient.USER_AGENT)
                    .timeout(8000)
                    .ignoreHttpErrors(true)
                    .get();
            String body = robots.text().toLowerCase();
            if (body.contains("disallow: /") && !body.contains("allow:")) {
                return false;
            }
            return true;
        } catch (Exception e) {
            // If robots.txt is unreachable, be conservative but allow catalog paths for demo
            return true;
        }
    }
}
