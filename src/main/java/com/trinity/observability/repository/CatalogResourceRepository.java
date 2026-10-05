package com.trinity.observability.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.trinity.observability.entity.CatalogResourceEntity;

/**
 * Registered resources, in PostgreSQL.
 *
 * <p>Every finder excludes soft-deleted rows. That is the whole reason the delete
 * is soft — an unregistered resource has to stay distinguishable from one that was
 * never registered, while being invisible to the registry — so a finder that
 * forgot the predicate would resurrect it.</p>
 */
@Repository
public interface CatalogResourceRepository extends JpaRepository<CatalogResourceEntity, Long> {

    List<CatalogResourceEntity> findByOrgAndDeletedAtIsNullOrderByRegisteredAtMicrosAsc(String org);

    Optional<CatalogResourceEntity> findByResourceUuidAndDeletedAtIsNull(UUID resourceUuid);

    /**
     * The live registration for one (category, product, name).
     *
     * <p>Matched case-insensitively on the name, and with the product compared
     * through COALESCE, for the same reason the unique index does both: an operator
     * retyping "PostgreSQL" as "postgresql" means the same resource, and in SQL
     * {@code NULL = NULL} is unknown, so a plain equality on a null product would
     * match nothing and let a duplicate through for exactly the categories that
     * have no product.</p>
     */
    @Query("""
            SELECT r FROM CatalogResourceEntity r
            WHERE r.org = :org
              AND r.category = :category
              AND COALESCE(r.productCode, '') = COALESCE(:productCode, '')
              AND LOWER(r.resourceName) = LOWER(:resourceName)
              AND r.deletedAt IS NULL
            """)
    Optional<CatalogResourceEntity> findLive(@Param("org") String org,
                                            @Param("category") String category,
                                            @Param("productCode") String productCode,
                                            @Param("resourceName") String resourceName);

    /**
     * Any live registration of this name in the category, whatever its product.
     *
     * <p>Backs the pre-product lookup the registry contract still exposes, so a
     * caller that does not know about products — the config seeder, and the
     * liveness refresher — keeps working unchanged.</p>
     */
    @Query("""
            SELECT r FROM CatalogResourceEntity r
            WHERE r.org = :org
              AND r.category = :category
              AND LOWER(r.resourceName) = LOWER(:resourceName)
              AND r.deletedAt IS NULL
            ORDER BY r.registeredAtMicros ASC
            """)
    List<CatalogResourceEntity> findLiveByName(@Param("org") String org,
                                               @Param("category") String category,
                                               @Param("resourceName") String resourceName);

    /** Per-product registration counts, for the category and product pickers. */
    @Query("""
            SELECT r.category, COALESCE(r.productCode, ''), r.status, COUNT(r)
            FROM CatalogResourceEntity r
            WHERE r.org = :org AND r.deletedAt IS NULL
            GROUP BY r.category, COALESCE(r.productCode, ''), r.status
            """)
    List<Object[]> countByCategoryProductAndStatus(@Param("org") String org);
}
