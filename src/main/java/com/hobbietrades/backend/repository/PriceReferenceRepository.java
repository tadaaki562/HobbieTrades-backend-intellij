package com.hobbietrades.backend.repository;

import com.hobbietrades.backend.model.PriceReference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PriceReferenceRepository extends JpaRepository<PriceReference, Long> {

    // Find by category and condition
    List<PriceReference> findByCategoryAndConditionLabel(String category, String conditionLabel);

    // Find by keyword and condition
    List<PriceReference> findByKeywordContainingIgnoreCaseAndConditionLabel(String keyword, String conditionLabel);

    // Find all by category
    List<PriceReference> findByCategory(String category);

    // Get average price for a category and condition
    @Query("SELECT AVG(p.avgPrice) FROM PriceReference p WHERE p.category = :category AND p.conditionLabel = :condition")
    Double getAveragePriceByCategoryAndCondition(@Param("category") String category, @Param("condition") String condition);

    Optional<PriceReference> findFirstByKeywordIgnoreCaseAndCategoryAndConditionLabel(
            String keyword, String category, String conditionLabel);
}