package com.trinity.observability.dto.productcatalog;

import java.util.Locale;

/**
 * The taxonomy a catalog resource is filed under.
 *
 * <p>These six values are the agreed categories, and they deliberately mirror
 * the front-end's existing {@code CATEGORIES} table in {@code config/products.js}
 * — the Product Catalog is the registry behind that same taxonomy, so the two
 * must not drift into different names for the same thing.</p>
 *
 * <h2>Why the code is not just {@code name()}</h2>
 *
 * <p>{@code AI_SERVICES} is what Java wants and {@code ai-services} is what the
 * UI already uses in URLs and local storage. {@link #getCode()} is the wire
 * form; {@link #fromCode(String)} accepts either, plus the display label, plus
 * any casing — a registration is rejected for a genuinely unknown category, not
 * for typing "AI Services" instead of "ai-services".</p>
 */
public enum ResourceCategory {

    APPLICATIONS("applications", "Applications",
            "Customer-facing product suites and their services."),
    PLATFORM("platform", "Platform",
            "Shared platform and infrastructure services."),
    AI_SERVICES("ai-services", "AI Services",
            "Model serving, inference and ML pipelines."),
    /**
     * Mobile front-ends and their backing services.
     *
     * <p>Sits beside Applications and AI Services rather than at the end because
     * those three are the categories that carry a PRODUCT list, and a reader
     * scanning the rail should find them together.</p>
     */
    MOBILE_APPLICATION("mobile-application", "Mobile Application",
            "Mobile apps and the services that back them."),
    TOOLS("tools", "Tools",
            "Open-source engines the platform runs on."),
    DATABASES("databases", "Databases",
            "Datastores and persistence services."),
    DLH("dlh", "DLH",
            "Data Lake House.");

    private final String code;
    private final String displayName;
    private final String description;

    ResourceCategory(String code, String displayName, String description) {
        this.code = code;
        this.displayName = displayName;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Resolve a caller-supplied category.
     *
     * @param raw the enum name, the wire code, or the display label, in any
     *            casing, with spaces or hyphens interchangeable
     * @return the matching category
     * @throws IllegalArgumentException when nothing matches — mapped to 400 by
     *         {@code GlobalExceptionHandler}, which is the right answer for a
     *         category the caller invented
     */
    public static ResourceCategory fromCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("category is required. Allowed: " + allowedCodes());
        }
        String normalised = normalise(raw);
        for (ResourceCategory candidate : values()) {
            if (normalise(candidate.name()).equals(normalised)
                    || normalise(candidate.code).equals(normalised)
                    || normalise(candidate.displayName).equals(normalised)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "Unknown category: '" + raw + "'. Allowed: " + allowedCodes());
    }

    /** Lenient form used for comparison — case, spaces and hyphens folded away. */
    private static String normalise(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    /** Wire codes, for error messages and the categories endpoint. */
    public static String allowedCodes() {
        StringBuilder out = new StringBuilder();
        for (ResourceCategory candidate : values()) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(candidate.code);
        }
        return out.toString();
    }
}
