package com.trinity.observability.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AlertDescriptionNormalizer}.
 *
 * <p>The inputs below are taken VERBATIM from this deployment's own alert stream,
 * because the defects being fixed are artefacts of the specific templates in use —
 * invented examples would not have the trailing comma in the right place or the
 * leading separator at all, and both are the whole point.</p>
 */
class AlertDescriptionNormalizerTest {

    /** Exactly what `k8s-node-disk-critical` emits for one matched node. */
    private static final String SINGLE_BLOCK = """
            ----------------
            node: kubeworker15
            mount: /
            disk: 81.4% used
            summary: Clean docker images/logs or extend the volume""";

    /** Two nodes, showing the join comma landing inside the first block's last line. */
    private static final String TWO_BLOCKS = """
            ----------------
            node: kubeworker16
            memory: 70.8% used
            summary: Node kubeworker16 has high RAM usage,
            ----------------
            node: kubeworker15
            memory: 72.1% used
            summary: Node kubeworker15 has high RAM usage""";

    @Test
    void theLeadingSeparatorIsRemoved() {
        String text = AlertDescriptionNormalizer.normalize(SINGLE_BLOCK).text();

        assertFalse(text.contains("----"), "the separator is template scaffolding, not content: " + text);
        assertTrue(text.startsWith("node: kubeworker15"), text);
    }

    @Test
    void theTrailingJoinCommaIsRemoved() {
        String text = AlertDescriptionNormalizer.normalize(TWO_BLOCKS).text();

        // The template's list separator lands at the end of a LINE inside the text,
        // which is what makes it look like a typo rather than a delimiter.
        assertFalse(text.contains("usage,"),
                "the join separator must not survive inside the prose: " + text);
        assertTrue(text.contains("high RAM usage"), text);
    }

    @Test
    void eachEntityBecomesItsOwnBlock() {
        AlertDescriptionNormalizer.Normalized result = AlertDescriptionNormalizer.normalize(TWO_BLOCKS);

        assertEquals(2, result.blocks().size());
        assertEquals(2, result.affectedCount());
        assertEquals("kubeworker16", result.blocks().get(0).fields().get("node"));
        assertEquals("70.8% used", result.blocks().get(0).fields().get("memory"));
        assertEquals("kubeworker15", result.blocks().get(1).fields().get("node"));
    }

    @Test
    void blocksAreSeparatedByABlankLineNotBySeparators() {
        String text = AlertDescriptionNormalizer.normalize(TWO_BLOCKS).text();

        assertTrue(text.contains("\n\n"), "blocks stay visually distinct: " + text);
        assertEquals(2, text.split("\n\n").length);
    }

    @Test
    void theHeadlineNamesTheEntityTheMeasureAndTheRest() {
        String headline = AlertDescriptionNormalizer.normalize(TWO_BLOCKS).headline();

        // What a tooltip is for: which thing, how bad, and how many others. The
        // full text is one click away and must not be crammed into a hover.
        assertTrue(headline.contains("kubeworker16"), headline);
        assertTrue(headline.contains("memory 70.8% used"), headline);
        assertTrue(headline.contains("+1 more"), headline);
    }

    @Test
    void aSingleEntityHeadlineHasNoMoreSuffix() {
        String headline = AlertDescriptionNormalizer.normalize(SINGLE_BLOCK).headline();

        assertTrue(headline.startsWith("kubeworker15"), headline);
        assertFalse(headline.contains("more"), headline);
    }

    @Test
    void identicalRepeatedBlocksAreCollapsed() {
        // A template that renders the same sentence per entity produces genuinely
        // identical blocks only when the values match too — so collapsing them
        // removes noise without losing an entity.
        String repeated = SINGLE_BLOCK + "\n----------------\n"
                + SINGLE_BLOCK.replace("----------------\n", "");

        AlertDescriptionNormalizer.Normalized result = AlertDescriptionNormalizer.normalize(repeated);

        assertEquals(1, result.blocks().size(),
                "the same entity described twice is one affected entity");
    }

    @Test
    void normalisingIsIdempotent() {
        // Load-bearing: this runs at ingest AND on read, so a row stored clean must
        // survive a second pass unchanged. Without it, re-normalising would keep
        // eating structure until the description was one line.
        String once = AlertDescriptionNormalizer.normalize(TWO_BLOCKS).text();
        String twice = AlertDescriptionNormalizer.normalize(once).text();

        assertEquals(once, twice);
    }

    @Test
    void aPlainDescriptionWithNoBlocksIsKept() {
        String plain = "Disk usage on kubeworker11 is above the 70% limit.";

        AlertDescriptionNormalizer.Normalized result = AlertDescriptionNormalizer.normalize(plain);

        assertEquals(plain, result.text(), "a description that uses no blocks is still a description");
        assertEquals(1, result.affectedCount());
    }

    @Test
    void nullAndBlankProduceNothingRatherThanEmptyStructure() {
        assertNull(AlertDescriptionNormalizer.normalize(null).text());
        assertNull(AlertDescriptionNormalizer.normalize("").text());
        assertNull(AlertDescriptionNormalizer.normalize("   \n  \n ").text());
        assertNull(AlertDescriptionNormalizer.normalize(null).headline());
        assertEquals(0, AlertDescriptionNormalizer.normalize(null).affectedCount());
    }

    @Test
    void separatorsWithNoContentBetweenThemAreDropped() {
        AlertDescriptionNormalizer.Normalized result =
                AlertDescriptionNormalizer.normalize("----------------\n----------------\n");

        assertNull(result.text());
        assertEquals(0, result.affectedCount());
    }

    @Test
    void tooManyBlocksAreCappedAndTheTruncationIsStated() {
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            many.append("----------------\nnode: kubeworker").append(i)
                    .append("\nmemory: 7").append(i).append(".0% used\n");
        }

        AlertDescriptionNormalizer.Normalized result =
                AlertDescriptionNormalizer.normalize(many.toString(), null, 3);

        assertEquals(3, result.blocks().size());
        // Said, not silently cut: a description that stops mid-way reads as corrupt
        // rather than as abridged.
        assertTrue(result.text().contains("and 5 more"), result.text());
        assertTrue(result.headline().contains("+7 more"), result.headline());
    }

    @Test
    void aValueContainingAColonSurvives() {
        AlertDescriptionNormalizer.Normalized result = AlertDescriptionNormalizer.normalize("""
                ----------------
                mount: /var/lib/docker
                note: see runbook: DISK-01""");

        assertEquals("/var/lib/docker", result.blocks().get(0).fields().get("mount"));
        assertEquals("see runbook: DISK-01", result.blocks().get(0).fields().get("note"),
                "only the FIRST colon separates the key from the value");
    }

    @Test
    void identityPrefersTheEntityKeyOverTheFirstField() {
        AlertDescriptionNormalizer.Normalized result = AlertDescriptionNormalizer.normalize("""
                ----------------
                namespace: dev
                pod: kafka2-7578bc86bb-f2fgv
                memory: 80.2% of limit""");

        // `namespace` comes first in the text but `pod` is what the alert is ABOUT.
        assertEquals("kafka2-7578bc86bb-f2fgv", result.blocks().get(0).identity());
        assertTrue(result.headline().startsWith("kafka2-"), result.headline());
    }
}
