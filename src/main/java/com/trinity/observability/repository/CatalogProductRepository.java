package com.trinity.observability.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.trinity.observability.entity.CatalogProductEntity;

/**
 * The product tier's reference data.
 *
 * <p>Read-heavy and tiny — a few dozen rows that change when the inventory does.
 * Ordered by {@code sortOrder} then label so the register dialog's dropdown lists
 * products in the agreed order rather than alphabetically, which would separate
 * {@code trinityICCC – ICCC Operator} from {@code trinityICCC - Digital Twin}.</p>
 */
@Repository
public interface CatalogProductRepository extends JpaRepository<CatalogProductEntity, Long> {

    List<CatalogProductEntity> findByCategoryAndActiveTrueOrderBySortOrderAscLabelAsc(String category);

    List<CatalogProductEntity> findByActiveTrueOrderByCategoryAscSortOrderAsc();

    /** Includes inactive rows: a resource registered against a retired product
     *  must still be able to show that product's label. */
    Optional<CatalogProductEntity> findByCategoryAndCode(String category, String code);
}
