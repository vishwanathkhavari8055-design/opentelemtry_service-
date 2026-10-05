package com.trinity.observability.entity;

/**
 * Why a rule is in the enabled state it is in.
 *
 * <p>{@code enabled} stays the one thing that decides whether OpenObserve
 * evaluates the rule. This says what the operator MEANT by it, which a boolean
 * cannot: a rule written on Friday to start on Monday and a rule switched off at
 * 3am because it would not stop paging are both {@code enabled = false}, and
 * they need opposite treatment. Without this, the rules table can only say
 * "off" — and somebody eventually turns the staged one on.</p>
 */
public enum ActivationMode {

    /**
     * Live as written. The rule's {@code enabled} flag was decided when it was
     * saved and nothing is pending — this is every rule created before the
     * feature existed, and the default.
     */
    NOW,

    /**
     * Staged, waiting for a person. The rule is stored, valid and NOT being
     * evaluated; somebody turns it on when they are ready. No timer will do it.
     */
    LATER,

    /**
     * Staged, waiting for a clock. Becomes enabled once {@code activateAt}
     * passes, without anybody present.
     */
    SCHEDULED;

    /** True while the rule is deliberately held back rather than simply off. */
    public boolean isStaged() {
        return this == LATER || this == SCHEDULED;
    }
}
