package com.hobbietrades.backend.repository;

import com.hobbietrades.backend.model.ItemGalleryImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItemGalleryImageRepository extends JpaRepository<ItemGalleryImage, Long> {
    List<ItemGalleryImage> findByItemIdOrderBySlotAsc(Long itemId);
    Optional<ItemGalleryImage> findByItemIdAndSlot(Long itemId, int slot);
    void deleteByItemId(Long itemId);
}
