package com.trinity.observability.dto.productcatalog;

/**
 * One proven link between a registered resource and the telemetry that belongs
 * to it: "in the {@code logs} signal, stream {@code default}, column
 * {@code service_name} equals {@code IoTOpsApiSvc}".
 *
 * <p>A binding is only created from a probe that actually returned rows, which
 * is what makes it safe to interpolate into SQL later — the column is known to
 * exist on that stream, so the exclusion predicate cannot turn a working query
 * into a DataFusion error.</p>
 *
 * <p>{@code value} is the spelling telemetry uses, NOT the spelling the user
 * typed. That distinction matters on this deployment: {@code IoTOpsApiSvc} and
 * {@code iotopsapisvc} are two different services (SDK-reported production vs.
 * the k8s log scraper in dev), so filtering case-insensitively would disable
 * both when the operator meant one.</p>
 *
 * @param signal one of {@code logs}, {@code traces}, {@code metrics}
 * @param stream the OpenObserve stream the match was found in
 * @param column the identifier column that matched
 * @param value  the exact value as stored in telemetry
 */
public record TelemetryBinding(String signal, String stream, String column, String value) {

    /** Compact form used by the snapshot store: {@code signal|stream|column|value}. */
    public String encode() {
        return signal + '|' + stream + '|' + column + '|' + value;
    }

    /**
     * Inverse of {@link #encode()}. Returns {@code null} for anything that is
     * not four non-blank parts — a malformed snapshot row is skipped rather
     * than failing the whole replay.
     */
    public static TelemetryBinding decode(String encoded) {
        if (encoded == null) {
            return null;
        }
        // limit=4 so a value containing '|' survives the round trip.
        String[] parts = encoded.split("\\|", 4);
        if (parts.length != 4) {
            return null;
        }
        for (String part : parts) {
            if (part.isBlank()) {
                return null;
            }
        }
        return new TelemetryBinding(parts[0], parts[1], parts[2], parts[3]);
    }
}
