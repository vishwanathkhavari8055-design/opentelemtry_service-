package com.trinity.observability.util;

import java.util.List;
import java.util.Locale;

/**
 * Ordering for the ALERT severity vocabulary, in one place.
 *
 * <h2>Why this exists when two severity classes already do</h2>
 *
 * <p>{@link SeverityRank} ranks the LOG vocabulary — TRACE, DEBUG, INFO, WARN,
 * ERROR, FATAL — which is a different ladder with different words, and using it
 * for alerts would silently fail to rank {@code disaster} at all.
 * {@link SeverityLadder} answers a different question again: it grades a numeric
 * observation against configured thresholds. Neither can say whether
 * {@code disaster} outranks {@code critical}, which is the one thing an incident
 * needs in order not to downgrade itself.</p>
 *
 * <h2>The order is data, not code</h2>
 *
 * <p>Callers pass the order in — see {@code alerts.incident.severity-order} — so a
 * deployment that adds a level does not need a code change. {@link #DEFAULT_ORDER}
 * is the fallback, and it deliberately matches the vocabulary
 * {@code AlertPayloadNormalizer.matchSeverity} already collapses onto: a ladder
 * that ranked words the normaliser never produces would rank nothing.</p>
 *
 * <p>No service, metric or threshold name appears here, and none may be added.
 * This class knows about severity words and nothing else.</p>
 */
public final class AlertSeverityRank {

    private AlertSeverityRank() {
    }

    /**
     * Least severe first, so a higher index is worse.
     *
     * <p>Ascending rather than descending because that is what makes
     * {@link #max(String, String, List)} a plain {@code >} comparison — a
     * descending list would invert the operator and read as a bug at every call
     * site.</p>
     */
    public static final List<String> DEFAULT_ORDER =
            List.of("info", "warning", "error", "critical", "disaster");

    /**
     * Position in the ladder, or -1 for a word not on it.
     *
     * <p>-1 rather than 0: an unrecognised severity must not silently rank as the
     * floor, because that would let "banana" outrank nothing and lose to
     * everything, quietly discarding a level somebody added to the config but
     * spelled differently.</p>
     */
    public static int rank(String severity, List<String> order) {
        if (severity == null || severity.isBlank()) {
            return -1;
        }
        String needle = severity.trim().toLowerCase(Locale.ROOT);
        List<String> ladder = (order == null || order.isEmpty()) ? DEFAULT_ORDER : order;
        for (int i = 0; i < ladder.size(); i++) {
            String rung = ladder.get(i);
            if (rung != null && rung.trim().toLowerCase(Locale.ROOT).equals(needle)) {
                return i;
            }
        }
        return -1;
    }

    /** {@link #rank(String, List)} against {@link #DEFAULT_ORDER}. */
    public static int rank(String severity) {
        return rank(severity, DEFAULT_ORDER);
    }

    /**
     * The worse of two severities.
     *
     * <p>This is what stops an incident downgrading itself. A rule evaluating every
     * minute reports whatever it sees on that evaluation, so a DISASTER at 10:34
     * followed by a WARNING at 10:35 is not the problem improving — it is one sample
     * of a condition that is still open. Taking the max keeps the incident at the
     * worst it has been for as long as it stays open; the episode ending is what
     * resets it, which is the existing recovery semantic rather than a new one.</p>
     *
     * <p>An unranked value loses to a ranked one, and two unranked values resolve to
     * the incoming one — a word this ladder does not know is not evidence of
     * anything, so it must not be able to overwrite a level that is known.</p>
     */
    public static String max(String current, String incoming, List<String> order) {
        if (current == null || current.isBlank()) {
            return incoming;
        }
        if (incoming == null || incoming.isBlank()) {
            return current;
        }
        int currentRank = rank(current, order);
        int incomingRank = rank(incoming, order);
        if (currentRank < 0 && incomingRank < 0) {
            // Neither is on the ladder. Prefer the newer value: it is at least the
            // most recent thing the source actually said.
            return incoming;
        }
        return incomingRank > currentRank ? incoming : current;
    }

    /** Whether {@code severity} is a word this ladder ranks. */
    public static boolean isKnown(String severity, List<String> order) {
        return rank(severity, order) >= 0;
    }
}
