package com.trinity.observability.util;

import java.util.List;
import java.util.Locale;

/**
 * Derives an alert's TYPE from the rule that produced it.
 *
 * <h2>The problem this solves</h2>
 *
 * <p>An incident is "this service, this thing going wrong" — and the same thing going
 * wrong more severely is the SAME incident escalating, not a second one. But
 * OpenObserve has no severity concept, so a common way to express severity levels is
 * one rule per level:</p>
 *
 * <pre>
 *   k8s-pod-cpu-warning      fires at 10:30
 *   k8s-pod-cpu-disaster     fires at 10:34
 * </pre>
 *
 * <p>Those are two rule NAMES describing one condition worsening. Keying an incident
 * on the raw name splits it in two, and the escalation is lost — the operator sees a
 * warning that apparently stopped and an unrelated disaster that appeared.</p>
 *
 * <p>So the type is the rule name with a trailing severity token removed. Both
 * examples above collapse onto {@code k8s-pod-cpu}, and the severity ladder in
 * {@link AlertSeverityRank} carries the level instead — which is where it belongs,
 * because that is the thing that knows disaster outranks warning.</p>
 *
 * <h2>It is a no-op for names that do not encode severity</h2>
 *
 * <p>Both conventions exist in the same deployment. {@code IoTOpsCIMService_high_cpu}
 * carries no severity token and passes through untouched, so nothing has to be
 * migrated or renamed for this to be safe. Only a name ENDING in a severity word is
 * affected, and only the final token is removed —
 * {@code disaster-recovery-check} keeps its name, because {@code disaster} is not
 * where the suffix would be.</p>
 *
 * <h2>Nothing here names a service or a metric</h2>
 *
 * <p>The only vocabulary is severity, and it arrives as a parameter. There is no list
 * of metrics, no list of services, and no place for one: a new metric or a new service
 * needs no change to this class, which is the whole point of deriving the type instead
 * of enumerating types.</p>
 */
public final class AlertTypeNormalizer {

    private AlertTypeNormalizer() {
    }

    /**
     * Separators a rule name may use before its severity token.
     *
     * <p>All three appear in real rule names ({@code k8s-pod-cpu-disaster},
     * {@code IoTOpsCIMService_high_cpu}, {@code "Pod CPU critical"}), and a name may
     * mix them. A severity word NOT preceded by one of these is not a suffix — it is
     * part of a longer word, and {@code catastrophic} must not be read as
     * {@code catastroph} plus a level.</p>
     */
    private static final char[] SEPARATORS = { '-', '_', ' ', '.', ':' };

    /**
     * The incident type for a rule name.
     *
     * @param alertName the rule name as it arrived; may be null or blank
     * @param severityOrder the severity vocabulary, from
     *        {@code alerts.incident.severity-order}; null falls back to
     *        {@link AlertSeverityRank#DEFAULT_ORDER}
     * @param stripSeveritySuffix false returns the name unchanged, for a deployment
     *        whose rule names genuinely end in a severity word that is not a level
     * @return the type, or null when there is no name to derive one from
     */
    public static String incidentType(String alertName, List<String> severityOrder,
            boolean stripSeveritySuffix) {
        if (alertName == null || alertName.isBlank()) {
            return null;
        }
        String name = alertName.trim();
        if (!stripSeveritySuffix) {
            return name;
        }

        List<String> ladder = (severityOrder == null || severityOrder.isEmpty())
                ? AlertSeverityRank.DEFAULT_ORDER : severityOrder;

        String lower = name.toLowerCase(Locale.ROOT);
        for (String severity : ladder) {
            String stripped = stripSuffix(name, lower, severity);
            if (stripped != null) {
                return stripped;
            }
        }
        return name;
    }

    /**
     * The name with {@code severity} removed as its suffix, or null when the name does
     * not end in that severity token.
     */
    private static String stripSuffix(String name, String lower, String severity) {
        if (severity == null || severity.isBlank()) {
            return null;
        }
        String token = severity.trim().toLowerCase(Locale.ROOT);
        if (!lower.endsWith(token)) {
            return null;
        }
        int cut = name.length() - token.length();
        // The token has to BE the suffix, preceded by a separator. Without this
        // check "catastrophic" ends with nothing useful but a name like
        // "hyperwarning" would lose its tail and become "hyper".
        if (cut <= 0 || !isSeparator(name.charAt(cut - 1))) {
            return null;
        }
        String stripped = name.substring(0, cut - 1).trim();
        // Refuse to strip a name down to nothing: a rule literally called
        // "critical" is a poor name but it is still the only identity it has,
        // and an empty type would collapse every such rule into one incident.
        return stripped.isEmpty() ? name : stripped;
    }

    /** {@link #incidentType(String, List, boolean)} with stripping on and the default ladder. */
    public static String incidentType(String alertName) {
        return incidentType(alertName, AlertSeverityRank.DEFAULT_ORDER, true);
    }

    private static boolean isSeparator(char c) {
        for (char separator : SEPARATORS) {
            if (c == separator) {
                return true;
            }
        }
        return false;
    }
}
