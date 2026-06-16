package com.hobbietrades.backend.service;

import com.hobbietrades.backend.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.*;
import java.util.stream.Stream;

@Service
public class ListingMaintenanceService {

    private final ItemRepository itemRepository;
    private final ItemGalleryImageRepository galleryRepository;
    private final TradeRepository tradeRepository;
    private final MessageRepository messageRepository;
    private final ReviewRepository reviewRepository;

    @Value("${upload.dir:uploads/}")
    private String uploadDir;

    public ListingMaintenanceService(
            ItemRepository itemRepository,
            ItemGalleryImageRepository galleryRepository,
            TradeRepository tradeRepository,
            MessageRepository messageRepository,
            ReviewRepository reviewRepository) {
        this.itemRepository = itemRepository;
        this.galleryRepository = galleryRepository;
        this.tradeRepository = tradeRepository;
        this.messageRepository = messageRepository;
        this.reviewRepository = reviewRepository;
    }

    /** Removes every listing and related trades, messages, reviews, and gallery blobs. */
    @Transactional
    public WipeResult wipeAllListings() {
        long reviews = reviewRepository.count();
        long messages = messageRepository.count();
        long trades = tradeRepository.count();
        long gallery = galleryRepository.count();
        long items = itemRepository.count();

        reviewRepository.deleteAll();
        messageRepository.deleteAll();
        tradeRepository.deleteAll();
        galleryRepository.deleteAll();
        itemRepository.deleteAll();

        int filesRemoved = cleanUploadDirectory();

        return new WipeResult(items, trades, messages, reviews, gallery, filesRemoved);
    }

    private int cleanUploadDirectory() {
        Path dir = Paths.get(uploadDir);
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> paths = Files.list(dir)) {
            for (Path p : paths.toList()) {
                if (Files.isRegularFile(p)) {
                    Files.deleteIfExists(p);
                    removed++;
                }
            }
        } catch (IOException e) {
            System.out.println("[Wipe] Could not clean upload dir: " + e.getMessage());
        }
        return removed;
    }

    public record WipeResult(
            long itemsRemoved,
            long tradesRemoved,
            long messagesRemoved,
            long reviewsRemoved,
            long galleryImagesRemoved,
            int diskFilesRemoved) {}
}
