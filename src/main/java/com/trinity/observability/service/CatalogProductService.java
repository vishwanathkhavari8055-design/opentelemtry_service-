package com.trinity.observability.service;

import java.util.List;
import java.util.Optional;

import com.trinity.observability.dto.productcatalog.ProductCatalogResponseDTO;
import com.trinity.observability.dto.productcatalog.ResourceCategory;

/**
 * The product tier — the middle level between a category and a microservice.
 *
 * <h2>Why there is a tier here at all</h2>
 *
 * <p>"Applications" is sixteen distinct products, and registering a microservice
 * under the category alone loses the only piece of structure that makes those
 * sixteen navigable. Category → PRODUCT → microservice is the shape the inventory
 * actually has.</p>
 *
 * <h2>Not every category has one</h2>
 *
 * <p>Only Applications, AI Services and Mobile Application carry a product list.
 * Tools, Databases, Platform and DLH are registered by bare name, and inventing a
 * synthetic product for them would put an empty level in the tree for four of the
 * seven categories. {@link #hasProducts(ResourceCategory)} is what the register
 * dialog asks so it knows whether to require a choice.</p>
 */
public interface CatalogProductService {

    /**
     * True when this category has products, so a registration under it must name one.
     *
     * <p>Derived from the data — whether any active product row exists — rather than
     * from a list in code. Adding the first product to a category is then an INSERT
     * that immediately starts being enforced, with nothing to keep in step.</p>
     */
    boolean hasProducts(ResourceCategory category);

    /**
     * The options for a picker, with live registration counts.
     *
     * @param category null for every category's products, so the UI can load once
     *                 and filter locally as the operator changes category
     */
    ProductCatalogResponseDTO.ProductList list(ResourceCategory category);

    /**
     * Resolve a caller-supplied product against a category.
     *
     * @param raw the product code, or its label — an operator pasting what they see
     *            on screen should not get a validation error
     * @return the resolved code, or empty when nothing matches
     */
    Optional<String> resolveCode(ResourceCategory category, String raw);

    /** Display label for a stored code, falling back to the code itself. */
    String labelFor(ResourceCategory category, String code);

    /** Every product's code in a category, in the agreed display order. */
    List<String> codesFor(ResourceCategory category);
}
