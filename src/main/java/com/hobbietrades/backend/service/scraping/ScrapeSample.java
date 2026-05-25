package com.hobbietrades.backend.service.scraping;

import java.util.List;

public record ScrapeSample(
        String source,
        String keyword,
        double medianPrice,
        int sampleCount,
        List<Double> rawPrices
) {}
