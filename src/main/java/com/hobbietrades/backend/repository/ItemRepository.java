package com.hobbietrades.backend.repository;

import com.hobbietrades.backend.model.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ItemRepository extends JpaRepository<Item, Long> {

    /** All items by user (including unavailable) — for internal use */
    List<Item> findByUserId(Long userId);

    /** Available listings only — used by profile page (fixes the delete/still-shows bug) */
    List<Item> findByUserIdAndIsAvailableTrue(Long userId);

    /** All available items — candidate pool for MatchingService */
    List<Item> findByIsAvailableTrue();

    /** Available items by category */
    List<Item> findByCategoryAndIsAvailableTrue(String category);

    /**
     * FIX: ItemController calls this method — was missing from the repository.
     * Spring Data JPA derives the query automatically from the method name.
     */
    List<Item> findByTitleContainingIgnoreCaseAndIsAvailableTrue(String title);

    /** Full-text search across title and description (available items only) */
    @Query("SELECT i FROM Item i WHERE i.isAvailable = true AND " +
            "(LOWER(i.title) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
            " LOWER(i.description) LIKE LOWER(CONCAT('%', :q, '%')))")
    List<Item> searchAvailable(@Param("q") String query);

    /** Items within a value range (available only) */
    @Query("SELECT i FROM Item i WHERE i.isAvailable = true AND " +
            "i.estimatedValue BETWEEN :min AND :max")
    List<Item> findByValueRange(@Param("min") java.math.BigDecimal min,
                                @Param("max") java.math.BigDecimal max);
}