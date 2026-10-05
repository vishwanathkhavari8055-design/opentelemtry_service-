package com.trinity.observability.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Repairs datasource references a dashboard model points at but never declares.
 *
 * <h2>The failure this exists for</h2>
 *
 * <p>A dashboard exported from one Grafana and imported into another carries
 * {@code ${DS_SOMETHING}} where its datasource used to be — Grafana's export
 * rewrites every datasource reference into a placeholder, and the import wizard
 * is what substitutes real UIDs back in. When a dashboard is imported by pasting
 * JSON, or edited afterwards, that substitution can land on the PANEL and miss
 * the TARGETS underneath it. The model is then internally inconsistent: the panel
 * names a real datasource, its queries name a variable that
 * {@code templating.list} never declares.</p>
 *
 * <p>Grafana's own UI papers over this — it falls back to the panel's datasource
 * when a query's is unusable. This render path does not: the browser sends the
 * target's reference verbatim to {@code /api/dashboards/query}, Grafana cannot
 * find a datasource called {@code ${DS_PROMETHEUS}}, and the whole request comes
 * back 404. Every panel whose targets escaped substitution shows an error while
 * the panels beside it — the ones whose targets kept a real UID — draw normally.
 * That is the "dashboard loads partially" report: not a slow query and not a
 * permissions problem, but a few panels that can never succeed.</p>
 *
 * <h2>What it will and will not do</h2>
 *
 * <p>Only references that are <em>provably</em> dead are touched: the reference
 * has to be a variable expression, and that variable has to be absent from
 * {@code templating.list}. A dashboard whose variables are properly declared
 * passes through byte for byte. References given as a plain datasource name or as
 * a concrete UID are left alone; the browser resolves those already, and
 * second-guessing them here would break the case that works.</p>
 *
 * <p>The replacement is always taken from within the same panel, never invented:
 * a dead target reference inherits the panel's datasource, and a dead panel
 * reference inherits the first live one among its own targets. When a panel names
 * no live datasource anywhere, it is left exactly as Grafana returned it — a
 * panel that fails is bad, but a panel silently pointed at a datasource nobody
 * chose is worse.</p>
 */
public final class DashboardDatasourceNormalizer {

    private DashboardDatasourceNormalizer() { }

    private static final String FIELD_DATASOURCE = "datasource";

    private static final Pattern BRACE_VAR = Pattern.compile("^\\s*\\$\\{\\s*(\\w+)\\s*(?::[^}]*)?\\}\\s*$");
    private static final Pattern BARE_VAR = Pattern.compile("^\\s*\\$(\\w+)\\s*$");
    private static final Pattern BRACKET_VAR = Pattern.compile("^\\s*\\[\\[\\s*(\\w+)\\s*(?::[^\\]]*)?\\]\\]\\s*$");

    /**
     * How deep panel nesting is followed. Rows holding panels is the only nesting
     * Grafana produces; the ceiling is here so a hand-edited model with a cycle
     * cannot turn a dashboard open into an unbounded walk.
     */
    private static final int MAX_PANEL_DEPTH = 8;

    /**
     * The model to hand the browser, and a note of what was repaired to get it.
     *
     * @param model   the dashboard model. The input is never mutated: a repaired
     *                model is a copy, and a clean one is the original node.
     * @param repairs one line per rewritten reference, for the log. Empty when the
     *                model needed nothing, which is the normal case.
     */
    public record Result(JsonNode model, List<String> repairs) {

        public boolean changed() {
            return !repairs.isEmpty();
        }
    }

    /**
     * Repair {@code model}, or hand it back untouched when it needs nothing.
     *
     * <p>Null-tolerant and shape-tolerant throughout: this runs on whatever a
     * Grafana of any version returned, and a model it cannot make sense of has to
     * render exactly as it does today rather than fail here.</p>
     */
    public static Result normalize(JsonNode model) {
        if (model == null || !model.isObject()) {
            return new Result(model, List.of());
        }

        Set<String> declared = declaredVariables(model);
        ObjectNode working = model.deepCopy();
        List<String> repairs = new ArrayList<>();

        repairPanels(working.path("panels"), declared, repairs, 1);

        // The copy is only worth keeping if something was actually repaired.
        return repairs.isEmpty() ? new Result(model, List.of()) : new Result(working, repairs);
    }

    /**
     * The variable names the dashboard declares.
     *
     * <p>{@code templating.list} only. An {@code __inputs} entry is deliberately
     * NOT counted as a declaration: it records what an import was supposed to ask
     * for, and a model still carrying one at render time is precisely the model
     * whose substitution never happened.</p>
     */
    private static Set<String> declaredVariables(JsonNode model) {
        Set<String> names = new HashSet<>();
        for (JsonNode variable : model.path("templating").path("list")) {
            String name = variable.path("name").asText("");
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    private static void repairPanels(JsonNode panels, Set<String> declared, List<String> repairs,
            int depth) {
        if (panels == null || !panels.isArray() || depth > MAX_PANEL_DEPTH) {
            return;
        }
        for (JsonNode panel : panels) {
            if (panel.isObject()) {
                repairPanel((ObjectNode) panel, declared, repairs);
                // A collapsed row carries its children inline.
                repairPanels(panel.path("panels"), declared, repairs, depth + 1);
            }
        }
    }

    private static void repairPanel(ObjectNode panel, Set<String> declared, List<String> repairs) {
        JsonNode targets = panel.path("targets");
        JsonNode panelRef = panel.get(FIELD_DATASOURCE);

        // The panel's own reference first, so its targets can inherit a repaired one.
        if (isDead(panelRef, declared)) {
            JsonNode inherited = firstLiveTargetRef(targets, declared);
            if (inherited == null) {
                // Nothing in this panel names a datasource that exists. Leaving it
                // alone keeps today's behaviour rather than guessing at one.
                return;
            }
            panel.set(FIELD_DATASOURCE, inherited.deepCopy());
            repairs.add(describe(panel, null, panelRef, inherited));
            panelRef = panel.get(FIELD_DATASOURCE);
        }

        repairTargets(panel, targets, panelRef, declared, repairs);
    }

    /** Points every target whose reference is a dead variable at the panel's (live) reference. */
    private static void repairTargets(ObjectNode panel, JsonNode targets, JsonNode panelRef,
            Set<String> declared, List<String> repairs) {
        if (panelRef == null || panelRef.isNull() || !targets.isArray()) {
            return;
        }
        for (JsonNode target : targets) {
            if (target.isObject()) {
                repairTarget(panel, target, panelRef, declared, repairs);
            }
        }
    }

    private static void repairTarget(ObjectNode panel, JsonNode target, JsonNode panelRef,
            Set<String> declared, List<String> repairs) {
        JsonNode targetRef = target.get(FIELD_DATASOURCE);
        if (isDead(targetRef, declared)) {
            ((ObjectNode) target).set(FIELD_DATASOURCE, panelRef.deepCopy());
            repairs.add(describe(panel, target, targetRef, panelRef));
        }
    }

    /** The first target reference that is neither missing nor a dead variable. */
    private static JsonNode firstLiveTargetRef(JsonNode targets, Set<String> declared) {
        if (targets == null || !targets.isArray()) {
            return null;
        }
        for (JsonNode target : targets) {
            JsonNode ref = target.path(FIELD_DATASOURCE);
            if (!ref.isMissingNode() && !ref.isNull() && !isDead(ref, declared)) {
                return ref;
            }
        }
        return null;
    }

    /**
     * Whether a reference names a variable the dashboard never declares.
     *
     * <p>A reference is read as either a bare string (Grafana's pre-schema-36
     * spelling, usually a datasource NAME) or a {@code {type, uid}} object. Only
     * the variable case can be dead: a name or a UID is the browser's to resolve,
     * and an unresolvable one of those is a different bug with a different fix.</p>
     */
    private static boolean isDead(JsonNode ref, Set<String> declared) {
        String variable = variableName(ref);
        return variable != null && !declared.contains(variable);
    }

    /** The variable a reference consists of, or null when it is not one. */
    private static String variableName(JsonNode ref) {
        if (ref == null) {
            return null;
        }
        String raw;
        if (ref.isTextual()) {
            raw = ref.asText();
        } else if (ref.isObject() && ref.path("uid").isTextual()) {
            raw = ref.path("uid").asText();
        } else {
            return null;
        }

        Matcher m1 = BRACE_VAR.matcher(raw);
        if (m1.matches()) {
            return m1.group(1);
        }
        Matcher m2 = BARE_VAR.matcher(raw);
        if (m2.matches()) {
            return m2.group(1);
        }
        Matcher m3 = BRACKET_VAR.matcher(raw);
        if (m3.matches()) {
            return m3.group(1);
        }
        return null;
    }

    private static String describe(JsonNode panel, JsonNode target, JsonNode from, JsonNode to) {
        StringBuilder out = new StringBuilder("panel ").append(panel.path("id").asText("?"))
                .append(" \"").append(panel.path("title").asText("")).append('"');
        if (target != null) {
            out.append(" target ").append(target.path("refId").asText("?"));
        }
        return out.append(": ").append(render(from)).append(" -> ").append(render(to)).toString();
    }

    private static String render(JsonNode ref) {
        if (ref == null || ref.isNull()) {
            return "(none)";
        }
        return ref.isTextual() ? ref.asText() : ref.path("uid").asText(ref.toString());
    }
}
