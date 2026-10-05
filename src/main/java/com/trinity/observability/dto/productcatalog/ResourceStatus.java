package com.trinity.observability.dto.productcatalog;

import java.util.Locale;

/**
 * Whether a registered resource's telemetry is surfaced by the rest of the
 * application.
 *
 * <p>{@link #DISABLED} is not a soft delete: the registration stays, and the
 * only consequence is that {@code CatalogTelemetryFilter} excludes the
 * resource's identifiers from every logs / traces / metrics query. Re-enabling
 * restores it with no re-validation.</p>
 */
public enum ResourceStatus {

    ENABLED,
    DISABLED;

    /** Lenient parse for query parameters. {@code null} / blank / "ALL" → null. */
    public static ResourceStatus fromCodeOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(Locale.ROOT);
        if ("ALL".equals(trimmed)) {
            return null;
        }
        for (ResourceStatus candidate : values()) {
            if (candidate.name().equals(trimmed)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "Unknown status: '" + raw + "'. Allowed: ENABLED, DISABLED, ALL");
    }
}
