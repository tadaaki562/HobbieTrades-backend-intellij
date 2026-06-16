package com.hobbietrades.backend.config;

import com.hobbietrades.backend.service.ListingMaintenanceService;
import com.hobbietrades.backend.service.ListingMaintenanceService.WipeResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * One-time listing wipe when HOBBIETRADES_WIPE_LISTINGS_ON_STARTUP=true.
 * Set to false again after deploy so new listings are kept.
 */
@Component
@Order(1)
public class ListingWipeStartupRunner implements ApplicationRunner {

    private final ListingMaintenanceService listingMaintenance;

    @Value("${hobbietrades.wipe-listings-on-startup:false}")
    private boolean wipeOnStartup;

    public ListingWipeStartupRunner(ListingMaintenanceService listingMaintenance) {
        this.listingMaintenance = listingMaintenance;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!wipeOnStartup) {
            return;
        }
        WipeResult result = listingMaintenance.wipeAllListings();
        System.out.println("[Startup] WIPE_LISTINGS enabled — removed "
                + result.itemsRemoved() + " items, "
                + result.tradesRemoved() + " trades, "
                + result.galleryImagesRemoved() + " gallery images.");
    }
}
