package com.trinity.observability.serviceimpl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.productcatalog.ProductCatalogResponseDTO;
import com.trinity.observability.dto.productcatalog.ResourceCategory;
import com.trinity.observability.dto.productcatalog.ResourceStatus;
import com.trinity.observability.entity.CatalogProductEntity;
import com.trinity.observability.repository.CatalogProductRepository;
import com.trinity.observability.repository.CatalogResourceRepository;
import com.trinity.observability.service.CatalogProductService;

/**
 * The product tier, read from PostgreSQL.
 *
 * <h2>Degrades instead of failing</h2>
 *
 * <p>The repositories arrive as {@link ObjectProvider} because they only exist when
 * the datasource does. With no database this service reports NO products and
 * {@link #hasProducts} answers false everywhere — which makes the product tier
 * simply absent rather than making registration impossible. A consumer using this
 * as a library without PostgreSQL keeps the catalog it had before products existed;
 * the alternative would be a startup failure for a feature they never asked for.</p>
 */
@Service
public class CatalogProductServiceImpl implements CatalogProductService {

    private static final Logger log = LogManager.getLogger(CatalogProductServiceImpl.class);

    private final ObjectProvider<CatalogProductRepository> products;
    private final ObjectProvider<CatalogResourceRepository> resources;
    private final OpenObserveConfig config;

    public CatalogProductServiceImpl(ObjectProvider<CatalogProductRepository> products,
            ObjectProvider<CatalogResourceRepository> resources,
            OpenObserveConfig config) {
        this.products = products;
        this.resources = resources;
        this.config = config;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasProducts(ResourceCategory category) {
        CatalogProductRepository repo = products.getIfAvailable();
        if (repo == null || category == null) {
            return false;
        }
        return !repo.findByCategoryAndActiveTrueOrderBySortOrderAscLabelAsc(category.name()).isEmpty();
    }

    @Override
    @Transactional(readOnly = true)
    public ProductCatalogResponseDTO.ProductList list(ResourceCategory category) {
        ProductCatalogResponseDTO.ProductList out = new ProductCatalogResponseDTO.ProductList();
        out.setOrg(config.getOrg());

        CatalogProductRepository repo = products.getIfAvailable();
        if (repo == null) {
            // Not an error: the product tier has no store. Said explicitly so the UI
            // can explain an empty picker instead of looking like a failed request.
            out.setSupported(false);
            return out;
        }

        List<CatalogProductEntity> rows = category == null
                ? repo.findByActiveTrueOrderByCategoryAscSortOrderAsc()
                : repo.findByCategoryAndActiveTrueOrderBySortOrderAscLabelAsc(category.name());

        Counts counts = countsFor(config.getOrg());

        List<ProductCatalogResponseDTO.Product> items = new ArrayList<>(rows.size());
        for (CatalogProductEntity row : rows) {
            ProductCatalogResponseDTO.Product dto = new ProductCatalogResponseDTO.Product();
            dto.setCode(row.getCode());
            dto.setLabel(row.getLabel());
            dto.setNote(row.getNote());
            ResourceCategory owning = categoryOf(row.getCategory());
            if (owning != null) {
                dto.setCategory(owning.getCode());
                dto.setCategoryLabel(owning.getDisplayName());
            }
            String key = row.getCategory() + "|" + row.getCode();
            dto.setRegisteredCount(counts.registered().getOrDefault(key, 0));
            dto.setEnabledCount(counts.enabled().getOrDefault(key, 0));
            items.add(dto);
        }
        out.setItems(items);
        out.setTotal(items.size());
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> resolveCode(ResourceCategory category, String raw) {
        CatalogProductRepository repo = products.getIfAvailable();
        if (repo == null || category == null || raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String needle = raw.trim();

        // Exact code first — the normal path, since the UI sends what it was given.
        Optional<CatalogProductEntity> byCode = repo.findByCategoryAndCode(category.name(), needle);
        if (byCode.isPresent()) {
            return Optional.of(byCode.get().getCode());
        }

        // Then the label, case-insensitively. Somebody pasting "trinityMOBILE -
        // Super App" out of the UI should not get a validation error for using the
        // words they can see.
        return codeByLabel(repo, category, fold(needle));
    }

    /** The code of the first active product whose label or code folds to {@code folded}. */
    private static Optional<String> codeByLabel(CatalogProductRepository repo,
            ResourceCategory category, String folded) {
        for (CatalogProductEntity row
                : repo.findByCategoryAndActiveTrueOrderBySortOrderAscLabelAsc(category.name())) {
            if (fold(row.getLabel()).equals(folded) || fold(row.getCode()).equals(folded)) {
                return Optional.of(row.getCode());
            }
        }
        return Optional.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public String labelFor(ResourceCategory category, String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        CatalogProductRepository repo = products.getIfAvailable();
        if (repo == null || category == null) {
            return code;
        }
        return repo.findByCategoryAndCode(category.name(), code)
                .map(CatalogProductEntity::getLabel)
                // A code whose product row is gone still renders as something
                // actionable; blanking it would claim the resource has no product.
                .orElse(code);
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> codesFor(ResourceCategory category) {
        CatalogProductRepository repo = products.getIfAvailable();
        if (repo == null || category == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (CatalogProductEntity row
                : repo.findByCategoryAndActiveTrueOrderBySortOrderAscLabelAsc(category.name())) {
            out.add(row.getCode());
        }
        return out;
    }

    /** Registration counts per product, in one query rather than one per option. */
    private record Counts(Map<String, Integer> registered, Map<String, Integer> enabled) {
    }

    private Counts countsFor(String org) {
        Map<String, Integer> registered = new HashMap<>();
        Map<String, Integer> enabled = new HashMap<>();
        CatalogResourceRepository repo = resources.getIfAvailable();
        if (repo == null) {
            return new Counts(registered, enabled);
        }
        for (Object[] row : repo.countByCategoryProductAndStatus(org)) {
            String category = String.valueOf(row[0]);
            String product = String.valueOf(row[1]);
            String status = String.valueOf(row[2]);
            int n = ((Number) row[3]).intValue();
            String key = category + "|" + product;
            registered.merge(key, n, Integer::sum);
            if (ResourceStatus.ENABLED.name().equalsIgnoreCase(status)) {
                enabled.merge(key, n, Integer::sum);
            }
        }
        return new Counts(registered, enabled);
    }

    private static ResourceCategory categoryOf(String raw) {
        try {
            return ResourceCategory.fromCode(raw);
        } catch (RuntimeException e) {
            log.warn("Product row has unknown category '{}'", raw);
            return null;
        }
    }

    /** Case- and separator-insensitive, so hyphen/en-dash/space differences match. */
    private static String fold(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replace('–', '-')
                .replace('—', '-')
                .replaceAll("[\\s_-]+", "");
    }
}
