package com.trinity.observability.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.trinity.observability.dto.productcatalog.ProductCatalogRequestDTO;
import com.trinity.observability.dto.productcatalog.ProductCatalogResponseDTO;
import com.trinity.observability.dto.productcatalog.ResourceCategory;
import com.trinity.observability.service.ProductCatalogService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * The Product Catalog: the registry of every monitored resource.
 *
 * <h2>Storage-independent by contract</h2>
 *
 * <p>Nothing on these routes reveals where a registration is kept. There is no
 * numeric row id, no version field, no pagination cursor tied to a table — an id
 * is an opaque string and a list is a list. That is what allows the in-memory
 * registry behind this to be replaced by a persistent one without any client
 * changing, which is the requirement.</p>
 *
 * <h2>Organization scoping</h2>
 *
 * <p>Every route is scoped to the request's organization via {@code ?org=} or
 * {@code X-Org-Id}, exactly like the rest of this service — a resource registered
 * while looking at {@code default} does not appear under {@code DLH}, because they
 * are different inventories with different telemetry.</p>
 *
 * <h2>Relationship to Logs / Traces / Metrics</h2>
 *
 * <p>Those APIs are UNCHANGED — same paths, same parameters, same response models.
 * What the catalog adds is a filter applied inside their existing queries, so a
 * disabled resource stops appearing in all three without any of them gaining a
 * parameter. See {@code CatalogTelemetryFilter}.</p>
 */
@RestController
@RequestMapping("/api/product-catalog")
@Tag(name = "Product Catalog",
        description = "Registry of monitored resources, validated against OpenObserve")
public class ProductCatalogController {

    private static final Logger log = LogManager.getLogger(ProductCatalogController.class);

    private final ProductCatalogService service;

    private final com.trinity.observability.service.CatalogProductService products;

    public ProductCatalogController(ProductCatalogService service,
            com.trinity.observability.service.CatalogProductService products) {
        this.service = service;
        this.products = products;
    }

    /**
     * The fixed category taxonomy.
     *
     * <p>Declared before {@code /{id}} so Spring's literal-path preference is not
     * the only thing keeping {@code categories} from being read as an id.</p>
     */
    @GetMapping("/categories")
    @Operation(summary = "List the resource categories",
            description = "Applications, Platform, AI Services, Tools, Databases and DLH, "
                    + "each with how many resources are registered under it. The set is fixed "
                    + "in code and mirrors the taxonomy the UI already uses.")
    public ProductCatalogResponseDTO.CategoryList categories() {
        log.info("GET /api/product-catalog/categories received");
        return service.listCategories();
    }

    /**
     * The product tier — what the register dialog's second dropdown offers.
     *
     * <p>Declared before {@code /{id}} for the same reason {@code categories} is:
     * relying only on Spring preferring a literal path over a variable leaves the
     * routing correct by accident rather than by construction.</p>
     */
    @GetMapping("/products")
    @Operation(summary = "List the products of a category",
            description = "The middle tier: category -> PRODUCT -> microservice. Applications, "
                    + "AI Services and Mobile Application carry a product list; Tools, "
                    + "Databases, Platform and DLH do not, and return an empty list rather than "
                    + "an error so one UI code path serves every category. "
                    + "Omit `category` to load every product at once, which is what the register "
                    + "dialog does so changing category filters locally instead of refetching.")
    public ProductCatalogResponseDTO.ProductList products(
            @RequestParam(name = "category", required = false) String category) {
        log.info("GET /api/product-catalog/products?category={} received", category);
        ResourceCategory resolved = (category == null || category.isBlank())
                ? null : ResourceCategory.fromCode(category);
        return products.list(resolved);
    }

    @GetMapping
    @Operation(summary = "List registered resources",
            description = "Filterable by search term, category and status. Last Seen is served "
                    + "from a short-lived cache; pass refresh=true to force a re-check against "
                    + "OpenObserve.")
    public ProductCatalogResponseDTO.ResourceList list(ProductCatalogRequestDTO.ListFilter filter) {
        log.info("GET /api/product-catalog received: {}", filter);
        ProductCatalogResponseDTO.ResourceList result = service.list(filter);
        log.info("GET /api/product-catalog returned {} resource(s) (supported={})",
                result.getTotal(), result.isSupported());
        return result;
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one registered resource",
            description = "Includes the identifier bindings discovered at registration — which "
                    + "stream and column this resource's telemetry is matched on.")
    public ProductCatalogResponseDTO.Resource get(@PathVariable("id") String id) {
        log.info("GET /api/product-catalog/{} received", id);
        return service.get(id);
    }

    /**
     * Check for telemetry without writing anything.
     *
     * <p>POST rather than GET despite being read-only: the resource name is
     * free-form user input, and putting it in a query string would land it in every
     * access log and proxy cache between the browser and here.</p>
     */
    @PostMapping("/validate")
    @Operation(summary = "Check whether a resource is emitting telemetry",
            description = "Runs the identifier probe chain against OpenObserve and reports what "
                    + "it found. Never fails for 'not found' — the outcome is in the response "
                    + "body's found flag and message, so the dialog can show the reason.")
    public ProductCatalogResponseDTO.ValidationResult validate(
            @Valid @RequestBody ProductCatalogRequestDTO.Validate body) {
        log.info("POST /api/product-catalog/validate received: {}", body);
        ProductCatalogResponseDTO.ValidationResult result = service.validate(body);
        log.info("POST /api/product-catalog/validate -> found={} for '{}'",
                result.isFound(), body.getResourceName());
        return result;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a resource",
            description = "Validates first. Answers 400 when OpenObserve holds no telemetry for "
                    + "the resource, and 409 when the same name is already registered in that "
                    + "category. Registration does not write to any database.")
    public ProductCatalogResponseDTO.Resource register(
            @Valid @RequestBody ProductCatalogRequestDTO.Register body) {
        log.info("POST /api/product-catalog/register received: category={}, resourceName={}",
                body.getCategory(), body.getResourceName());
        return service.register(body);
    }

    @PatchMapping("/{id}/enable")
    @Operation(summary = "Enable a resource",
            description = "Its logs, traces and metrics become visible across the application "
                    + "again. PATCH, so nothing but the status is touched.")
    public ProductCatalogResponseDTO.WriteResult enable(@PathVariable("id") String id) {
        log.info("PATCH /api/product-catalog/{}/enable received", id);
        return service.enable(id);
    }

    @PatchMapping("/{id}/disable")
    @Operation(summary = "Disable a resource",
            description = "Its telemetry stops appearing in Logs, Traces and Metrics anywhere in "
                    + "the application. The registration is kept and the data in OpenObserve is "
                    + "untouched.")
    public ProductCatalogResponseDTO.WriteResult disable(@PathVariable("id") String id) {
        log.info("PATCH /api/product-catalog/{}/disable received", id);
        return service.disable(id);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Remove a resource from the catalog",
            description = "Unregisters it. Note this is NOT the same as disabling: with the "
                    + "default filter mode an unregistered resource's telemetry becomes visible "
                    + "again, because only registered-and-disabled resources are hidden.")
    public ProductCatalogResponseDTO.WriteResult remove(@PathVariable("id") String id) {
        log.info("DELETE /api/product-catalog/{} received", id);
        return service.remove(id);
    }
}
