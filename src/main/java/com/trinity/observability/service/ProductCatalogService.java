package com.trinity.observability.service;

import com.trinity.observability.dto.productcatalog.ProductCatalogRequestDTO;
import com.trinity.observability.dto.productcatalog.ProductCatalogResponseDTO;

/**
 * The Product Catalog: the registry of monitored resources, with OpenObserve as
 * the single source of truth for whether a resource is real.
 *
 * <p>Storage-independent by construction — every method here is expressed in
 * terms of the DTOs the API exposes, and the only state it touches is behind
 * {@link com.trinity.observability.registry.ProductCatalogRegistry}. Swapping
 * that registry for a persistent one changes nothing on this interface, which is
 * what keeps the REST contract and the UI stable across such a change.</p>
 */
public interface ProductCatalogService {

    /** The fixed category taxonomy, with per-category registration counts. */
    ProductCatalogResponseDTO.CategoryList listCategories();

    /**
     * Every registered resource for the active organization, filtered and with
     * Last Seen refreshed subject to the cache TTL.
     */
    ProductCatalogResponseDTO.ResourceList list(ProductCatalogRequestDTO.ListFilter filter);

    /**
     * One resource.
     *
     * @throws com.trinity.observability.exception.ResourceNotFoundException when
     *         the id is not registered
     */
    ProductCatalogResponseDTO.Resource get(String id);

    /**
     * Check whether telemetry exists, without writing anything.
     *
     * <p>Never throws for "not found" — the dialog needs to render the reason,
     * not catch an error — so the outcome is carried in the result's
     * {@code found} and {@code message}.</p>
     */
    ProductCatalogResponseDTO.ValidationResult validate(ProductCatalogRequestDTO.Validate request);

    /**
     * Validate and register.
     *
     * @throws IllegalArgumentException on an unknown category or a blank name
     * @throws com.trinity.observability.exception.ResourceConflictException when
     *         the same name is already registered in that category
     * @throws com.trinity.observability.exception.TelemetryNotFoundException when
     *         OpenObserve holds no telemetry for the resource
     */
    ProductCatalogResponseDTO.Resource register(ProductCatalogRequestDTO.Register request);

    /** Surface this resource's telemetry across Logs, Traces and Metrics again. */
    ProductCatalogResponseDTO.WriteResult enable(String id);

    /** Hide this resource's telemetry everywhere in the application. */
    ProductCatalogResponseDTO.WriteResult disable(String id);

    /**
     * Unregister. The telemetry is untouched — OpenObserve keeps it, and with the
     * default filter mode it becomes visible again, because an unregistered
     * resource is not a disabled one.
     */
    ProductCatalogResponseDTO.WriteResult remove(String id);
}
