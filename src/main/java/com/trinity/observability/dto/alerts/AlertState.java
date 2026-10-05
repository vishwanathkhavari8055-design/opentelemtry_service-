package com.trinity.observability.dto.alerts;

import java.util.List;

/**
 * Lifecycle of one alert episode, and — because they are mostly the same
 * vocabulary — the kind of event that caused a transition into it.
 *
 * <p>One enum rather than a separate {@code EventType} and {@code State}: nearly
 * every event type names exactly the state it produces, and a second enum would
 * mostly create the possibility of the two disagreeing.</p>
 *
 * <h2>The one exception: REOPENED</h2>
 *
 * <p>{@link #REOPENED} is an EVENT ONLY. Reopening produces the {@link #FIRING}
 * state, so there is no such thing as an alert whose status is REOPENED — and the
 * database agrees: {@code ck_alerts_status} does not accept it while
 * {@code ck_alert_events_type} does. {@link #isAssignableStatus()} is what keeps
 * that distinction checkable in code rather than only at the point a constraint
 * violation is thrown.</p>
 */
public enum AlertState {

    /** OpenObserve's condition was met and the firing reached us. */
    FIRING,

    /**
     * A human has seen it. Survives re-firing — an operator who acknowledged an
     * alert does not want it to shout again on the next evaluation while they
     * are still working on it. The occurrence counter keeps rising so the UI can
     * still show "4 more firings since you acknowledged this".
     */
    ACKNOWLEDGED,

    /**
     * The condition is over — either closed by hand, or inferred from silence.
     *
     * <p>Inference is not a shortcut: OpenObserve notifies on evaluation and
     * never emits a "recovered" event, so the absence of further firings is
     * genuinely the only signal available.</p>
     */
    RESOLVED,

    /**
     * A human is finished with it.
     *
     * <p>Deliberately distinct from {@link #RESOLVED}. Resolution says the
     * condition cleared and is routinely inferred by the staleness sweep; closure
     * says somebody reviewed the incident and is never inferred. Collapsing the
     * two would make an alert that was actually triaged indistinguishable from
     * one that merely went quiet — which is exactly the distinction an on-call
     * handover depends on.</p>
     */
    CLOSED,

    /**
     * EVENT ONLY — never a status. Reopening moves the alert back to
     * {@link #FIRING}; this records that a person did so, and why.
     */
    REOPENED;

    /** States an alert row may actually hold. Excludes {@link #REOPENED}. */
    private static final List<AlertState> ASSIGNABLE =
            List.of(FIRING, ACKNOWLEDGED, RESOLVED, CLOSED);

    /** States that mean "no longer needs attention" — the closed set. */
    private static final List<AlertState> TERMINAL = List.of(RESOLVED, CLOSED);

    /** Lowercase form used on the wire. */
    public String wire() {
        return name().toLowerCase();
    }

    /**
     * Whether this value may be stored as an alert's status.
     *
     * @return false only for {@link #REOPENED}
     */
    public boolean isAssignableStatus() {
        return ASSIGNABLE.contains(this);
    }

    /**
     * Whether an alert in this state is off the active list.
     *
     * <p>Used by the open-episode lookup and the staleness sweep, which must
     * treat RESOLVED and CLOSED identically — a closed alert that still reserved
     * its fingerprint would make the next genuine firing fold into it instead of
     * opening a new episode.</p>
     */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** The states an alert row may hold, in lifecycle order. */
    public static List<AlertState> assignableStatuses() {
        return ASSIGNABLE;
    }

    /** The states that take an alert off the active list. */
    public static List<AlertState> terminalStatuses() {
        return TERMINAL;
    }

    /**
     * The transitions an alert in this state will actually accept.
     *
     * <p>Lives here rather than in the service so the {@code actions} array a row
     * carries and the {@code applyAction} check that guards the write cannot drift
     * apart. A UI that renders a button the API then refuses with a 400 is worse
     * than one that renders no button at all — it turns a disabled control into a
     * failed click on a live incident.</p>
     *
     * <p>Lowercase, and in the order they should be offered: the action an operator
     * most likely wants comes first.</p>
     *
     * @param state the alert's current status; null is treated as {@link #FIRING},
     *              which is what an alert with no status recorded actually is
     */
    public static List<String> availableActions(AlertState state) {
        AlertState current = state == null ? FIRING : state;
        return switch (current) {
            // Re-acknowledging is deliberately still offered: it hands ownership to
            // whoever is picking the alert up now, which is exactly what a shift
            // handover needs.
            case FIRING, ACKNOWLEDGED -> List.of("acknowledge", "resolve", "close");
            case RESOLVED -> List.of("reopen", "close");
            case CLOSED -> List.of("reopen");
            // Not a status any row can hold; see the class javadoc.
            case REOPENED -> List.of();
        };
    }

    /**
     * Parses a wire value, tolerating anything unrecognised.
     *
     * @return the matching state, or {@code fallback} for null/blank/unknown input
     */
    public static AlertState parse(String value, AlertState fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * Parses a value that must be a storable status.
     *
     * @throws IllegalArgumentException for an unknown value or for REOPENED,
     *         which is an event and not a state
     */
    public static AlertState parseStatus(String value) {
        AlertState parsed = parse(value, null);
        if (parsed == null || !parsed.isAssignableStatus()) {
            throw new IllegalArgumentException("Unknown alert status '" + value
                    + "' (expected one of " + ASSIGNABLE + ")");
        }
        return parsed;
    }
}
