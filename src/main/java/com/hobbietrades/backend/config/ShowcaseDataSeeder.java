package com.hobbietrades.backend.config;

import com.hobbietrades.backend.service.ShowcaseSeedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Runs showcase seed when {@code hobbietrades.seed.showcase=true} (set once on Render, then disable).
 */
@Component
public class ShowcaseDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ShowcaseDataSeeder.class);

    private final ShowcaseSeedService showcaseSeedService;

    @Value("${hobbietrades.seed.showcase:false}")
    private boolean seedShowcase;

    public ShowcaseDataSeeder(ShowcaseSeedService showcaseSeedService) {
        this.showcaseSeedService = showcaseSeedService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!seedShowcase) {
            return;
        }
        log.info("Showcase seed enabled — creating demo accounts and listings…");
        Map<String, Object> result = showcaseSeedService.seed();
        log.info("Showcase seed done: {} users, {} listings (password: {})",
                result.get("usersCreated"),
                result.get("listingsCreated"),
                ShowcaseSeedService.DEMO_PASSWORD);
    }
}
