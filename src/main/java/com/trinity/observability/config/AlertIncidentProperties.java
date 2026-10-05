package com.trinity.observability.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.context.properties.ConfigurationProperties;

import com.trinity.observability.util.AlertSeverityRank;

import jakarta.annotation.PostConstruct;

/**
 * How firings are aggregated into incidents.
 *
 * <p>An incident here is not a separate record — it IS an alert episode, keyed by
 * {@code alerts.ingest.identity-fields}. These properties govern the two decisions
 * that key cannot express on its own: how severity levels rank against each other,
 * and how much of the payload is projected back out for display.</p>
 *
 * <p>Everything here is vocabulary and limits. There is deliberately no property that
 * names a service, a metric or a threshold — those are the things that must work
 * without configuration, let alone code.</p>
 */
@ConfigurationProperties(prefix = "alerts.incident")
public class AlertIncidentProperties {

    private static final Logger log = LogManager.getLogger(AlertIncidentProperties.class);

    /**
     * Severity levels, LEAST severe first.
     *
     * <p>Used for two things that must agree: deciding whether an incoming firing
     * escalates an open incident, and deciding which trailing token to strip when
     * deriving the incident type from a rule name. Adding a level here teaches both
     * at once — which is the reason they share one list rather than each holding
     * their own.</p>
     */
    private List<String> severityOrder = new ArrayList<>(AlertSeverityRank.DEFAULT_ORDER);

    /**
     * Whether a trailing severity token is removed from a rule name to derive the
     * incident type.
     *
     * <p>On, because expressing severity as one rule per level is a normal
     * OpenObserve pattern and leaving it in the key splits one escalating condition
     * into two unrelated incidents. Turn it off for a deployment whose rule names
     * genuinely end in a severity word that is not a level.</p>
     */
    private boolean stripSeveritySuffix = true;

    /** Cap on projected payload attributes per alert, across values and labels. */
    private int maxAttributes = 60;

    /**
     * Payload keys left out of the projected attributes.
     *
     * <p>These are the long rendered blobs the row already shows in their own
     * columns. Repeating a 2,400-character description inside the attributes panel
     * would bury the handful of fields the panel exists to surface.</p>
     */
    private List<String> excludeAttributes = new ArrayList<>(List.of(
            "description", "summary", "text", "message", "body", "raw_payload"));

    @PostConstruct
    void validate() {
        // Refused rather than defaulted. A severity list that ranks nothing would let
        // every incident downgrade itself on the next firing, and it would do so
        // silently — the alerts would still appear, just at the wrong level.
        List<String> cleaned = new ArrayList<>();
        for (String level : severityOrder) {
            if (level != null && !level.isBlank()) {
                cleaned.add(level.trim().toLowerCase(Locale.ROOT));
            }
        }
        if (cleaned.isEmpty()) {
            throw new IllegalStateException(
                    "alerts.incident.severity-order is empty. Expected levels least-severe "
                            + "first, e.g. " + AlertSeverityRank.DEFAULT_ORDER);
        }
        if (cleaned.size() != cleaned.stream().distinct().count()) {
            throw new IllegalStateException(
                    "alerts.incident.severity-order contains a duplicate level: " + cleaned
                            + ". Each level must appear once, or its rank is ambiguous.");
        }
        this.severityOrder = cleaned;

        log.info("Alert incident severity ladder (least→most severe): {}", severityOrder);
        if (stripSeveritySuffix) {
            log.info("Incident type is derived by stripping a trailing severity token from the "
                    + "rule name, so e.g. '<rule>-warning' and '<rule>-disaster' aggregate into "
                    + "ONE incident that escalates. Set alerts.incident.strip-severity-suffix="
                    + "false to key on the rule name exactly as it arrives.");
        } else {
            log.warn("alerts.incident.strip-severity-suffix=false — rule names are keyed verbatim, "
                    + "so a deployment using one rule per severity level will show each level as "
                    + "a SEPARATE incident rather than one that escalates.");
        }
    }

    public List<String> getSeverityOrder() {
        return severityOrder;
    }

    public void setSeverityOrder(List<String> severityOrder) {
        this.severityOrder = new ArrayList<>(
                (severityOrder == null || severityOrder.isEmpty())
                        ? AlertSeverityRank.DEFAULT_ORDER
                        : severityOrder);
    }

    public boolean isStripSeveritySuffix() {
        return stripSeveritySuffix;
    }

    public void setStripSeveritySuffix(boolean stripSeveritySuffix) {
        this.stripSeveritySuffix = stripSeveritySuffix;
    }

    public int getMaxAttributes() {
        return maxAttributes;
    }

    public void setMaxAttributes(int maxAttributes) {
        this.maxAttributes = maxAttributes <= 0 ? 60 : maxAttributes;
    }

    public List<String> getExcludeAttributes() {
        return excludeAttributes;
    }

    public void setExcludeAttributes(List<String> excludeAttributes) {
        this.excludeAttributes = excludeAttributes == null
                ? new ArrayList<>() : new ArrayList<>(excludeAttributes);
    }
}
