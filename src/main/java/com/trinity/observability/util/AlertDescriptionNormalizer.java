package com.trinity.observability.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cleans up the alert description an OpenObserve template renders.
 *
 * <h2>What the raw text actually looks like</h2>
 *
 * <p>A template loops over the matched records, so a rule that matches six pods
 * emits six blocks joined together. Observed verbatim on this deployment:</p>
 *
 * <pre>
 * ----------------
 * pod: tiotuibuilderpagesvc-9b87b7769-8mhx5
 * namespace: dev
 * memory: 98.6% of limit
 * summary: High memory usage vs pod limit,
 * ----------------
 * pod: …
 * </pre>
 *
 * <p>Which gives three separate problems, none of them the template author's
 * fault — they are what looping inside a text template produces:</p>
 *
 * <ol>
 *   <li>A leading {@code ----------------} on every description.</li>
 *   <li>A trailing {@code ,} on each block's last line — the join separator,
 *       landing inside the text rather than between blocks.</li>
 *   <li>2,432 characters for one alert, most of it the same sentence repeated
 *       with different numbers.</li>
 * </ol>
 *
 * <p>And the KEYS differ per rule ({@code pod/namespace/memory} against
 * {@code node/mount/disk}), so there is no fixed schema to parse into. What IS
 * consistent is the shape: separator-delimited blocks of {@code key: value}
 * lines. That is what this class relies on, and the separator is configurable
 * because it is a property of somebody's template, not of the format.</p>
 *
 * <h2>Idempotent, deliberately</h2>
 *
 * <p>Normalising already-clean text is a no-op. That matters because this runs at
 * BOTH boundaries: at ingest, so what is stored is clean going forward, and on
 * read, so the rows already in the database render properly without waiting for
 * their alert to fire again — which for a resolved alert would be never.</p>
 */
public final class AlertDescriptionNormalizer {

    /** Default block separator: a run of four or more dashes on its own line. */
    private static final Pattern DEFAULT_SEPARATOR = Pattern.compile("(?m)^\\s*-{4,}\\s*$");

    /**
     * Trailing join artifact: a comma (optionally spaced) at the very end of a
     * block, left behind by the template's own list separator.
     */
    private static final Pattern TRAILING_JOIN = Pattern.compile("[,;]\\s*$");

    /** Collapses three or more newlines to two, so paragraphs stay but gaps do not. */
    private static final Pattern EXCESS_BLANK_LINES = Pattern.compile("\n{3,}");

    /**
     * Blank-line block boundary, used on the SECOND pass.
     *
     * <p>This is what makes the whole thing idempotent. A first pass replaces the
     * template's separators with blank lines, so a second pass sees no separator at
     * all — and without recognising a blank line as a boundary it would treat the
     * entire text as one block and fuse every entity together. That is not a
     * hypothetical: normalising runs at ingest and again on read.</p>
     */
    private static final Pattern BLANK_LINE = Pattern.compile("\n\\s*\n");

    /** The truncation marker this class appends, so re-reading it is not a "block". */
    private static final Pattern TRUNCATION_MARKER =
            Pattern.compile("^…?\\s*and \\d+ more$", Pattern.CASE_INSENSITIVE);

    /** A {@code key: value} line. The value may itself contain colons. */
    private static final Pattern KEY_VALUE = Pattern.compile("^([A-Za-z][A-Za-z0-9_. -]{0,40}):\\s*+(.*)$");

    private AlertDescriptionNormalizer() {
    }

    /**
     * One matched entity from the description.
     *
     * @param fields ordered {@code key → value} pairs as the template rendered them
     * @param text   the block's own text, cleaned
     */
    public record Block(Map<String, String> fields, String text) {

        /** The value most likely to name this entity, for a one-line headline. */
        public String identity() {
            for (String key : List.of("pod", "node", "service", "host", "instance", "container",
                    "deployment", "name")) {
                String value = fields.get(key);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            return fields.isEmpty() ? null : fields.values().iterator().next();
        }
    }

    /**
     * The cleaned description.
     *
     * @param text     blocks joined by a blank line, separators and join artifacts gone
     * @param blocks   one entry per matched entity
     * @param headline a single line fit for a tooltip or a table cell
     */
    public record Normalized(String text, List<Block> blocks, String headline) {

        public int affectedCount() {
            return blocks.size();
        }
    }

    /** {@link #normalize(String, Pattern, int)} with the default separator and cap. */
    public static Normalized normalize(String raw) {
        return normalize(raw, DEFAULT_SEPARATOR, 50);
    }

    /**
     * Clean a raw description.
     *
     * @param raw       the template's output; may be null
     * @param separator block separator; null uses the default
     * @param maxBlocks upper bound on retained blocks, so a rule matching ten
     *                  thousand rows cannot produce a megabyte of description
     * @return the cleaned form; never null, and {@code text} is null only when the
     *         input had no usable content
     */
    public static Normalized normalize(String raw, Pattern separator, int maxBlocks) {
        if (raw == null || raw.isBlank()) {
            return new Normalized(null, List.of(), null);
        }
        Pattern splitOn = separator == null ? DEFAULT_SEPARATOR : separator;

        // How the input delimits its entities, in order of confidence:
        //   1. the template's separator, on a first pass
        //   2. a blank line, on a second pass over already-normalised text
        //   3. not at all — a plain one-paragraph description
        //
        // Deciding this up front rather than falling through on "no blocks found"
        // is what keeps the two passes agreeing. Falling through re-cleaned the
        // WHOLE input as a single block, which both resurrected the separators and
        // fused every entity together on the second pass.
        List<String> distinct = distinctBlocks(splitBlocks(raw, splitOn));

        // Everything past the cap is counted rather than kept.
        int kept = Math.max(0, Math.min(maxBlocks, distinct.size()));
        int dropped = distinct.size() - kept;
        List<Block> blocks = new ArrayList<>();
        for (String cleaned : distinct.subList(0, kept)) {
            blocks.add(new Block(parseFields(cleaned), cleaned));
        }

        if (blocks.isEmpty()) {
            // Separators with nothing between them, or whitespace only. There is no
            // description here, and inventing one from the scaffolding would put
            // "----------------" on the screen.
            return new Normalized(null, List.of(), null);
        }

        return new Normalized(joinText(blocks, dropped), List.copyOf(blocks),
                headlineOf(blocks, dropped));
    }

    /** Splits the input on its most confident delimiter, as described in normalize. */
    private static String[] splitBlocks(String raw, Pattern splitOn) {
        if (splitOn.matcher(raw).find()) {
            return splitOn.split(raw);
        } else if (BLANK_LINE.matcher(raw).find()) {
            return BLANK_LINE.split(raw);
        } else {
            return new String[] { raw };
        }
    }

    /** Cleaned, non-empty, distinct blocks in their original order. */
    private static List<String> distinctBlocks(String[] rawBlocks) {
        // De-duplicate on the block's exact text. A template that repeats an
        // identical sentence per entity produces genuinely identical blocks only
        // when the values match too, so this removes noise without losing entities.
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String rawBlock : rawBlocks) {
            String cleaned = cleanBlock(rawBlock);
            if (!cleaned.isEmpty() && !TRUNCATION_MARKER.matcher(cleaned).matches()) {
                seen.add(cleaned);
            }
        }
        return new ArrayList<>(seen);
    }

    /** Blocks joined by a blank line, plus a marker for any that were dropped. */
    private static String joinText(List<Block> blocks, int dropped) {
        StringBuilder text = new StringBuilder();
        for (Block block : blocks) {
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(block.text());
        }
        if (dropped > 0) {
            // Said in the text rather than silently truncated: a description that
            // stops mid-way reads as corrupt.
            text.append("\n\n… and ").append(dropped).append(" more");
        }
        return text.toString();
    }

    /** Strips separators, the trailing join artifact and surrounding whitespace. */
    private static String cleanBlock(String rawBlock) {
        if (rawBlock == null) {
            return "";
        }
        String cleaned = rawBlock.strip();
        cleaned = EXCESS_BLANK_LINES.matcher(cleaned).replaceAll("\n\n");

        // Per LINE, not just at the end of the block: the join artifact lands on
        // whichever line happened to be last, and a block can be re-joined later.
        String[] lines = cleaned.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String trimmed = TRAILING_JOIN.matcher(line.strip()).replaceAll("");
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(trimmed);
        }
        return out.toString();
    }

    /** Parses the block's {@code key: value} lines, preserving their order. */
    private static Map<String, String> parseFields(String block) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : block.split("\n")) {
            var matcher = KEY_VALUE.matcher(line.strip());
            if (matcher.matches()) {
                String key = matcher.group(1).strip().toLowerCase(java.util.Locale.ROOT);
                String value = matcher.group(2).strip();
                if (!value.isEmpty()) {
                    // putIfAbsent: the first occurrence wins, so a repeated key
                    // across re-joined blocks does not overwrite the entity we are
                    // describing with a later one's value.
                    fields.putIfAbsent(key, value);
                }
            }
        }
        return fields;
    }

    /**
     * A single line fit for a tooltip.
     *
     * <p>Names the first entity and the measurement that triggered the alert, then
     * says how many others there are — which is the information a hover is for.
     * The full text is one click away in the details panel.</p>
     */
    private static String headlineOf(List<Block> blocks, int dropped) {
        if (blocks.isEmpty()) {
            return null;
        }
        Block first = blocks.get(0);
        String identity = first.identity();

        // The measurement is whichever field is not identity or prose — a percentage
        // or a value. Looked up by name rather than guessed, because the key set
        // differs per rule.
        String measure = null;
        for (String key : List.of("memory", "cpu", "disk", "value", "usage", "load", "count")) {
            String value = first.fields().get(key);
            if (value != null && !value.isBlank()) {
                measure = key + " " + value;
                break;
            }
        }

        StringBuilder headline = new StringBuilder();
        headline.append(identity == null ? first.text().lines().findFirst().orElse("") : identity);
        if (measure != null) {
            headline.append(" — ").append(measure);
        }

        int others = blocks.size() - 1 + dropped;
        if (others > 0) {
            headline.append(" (+").append(others).append(" more)");
        }
        return headline.toString();
    }
}
