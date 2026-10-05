package com.trinity.observability.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.trinity.observability.config.AlertIngestProperties;
import com.trinity.observability.dto.alerts.AlertEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * What makes two firings the SAME alert.
 *
 * <p>This is the identity that everything else hangs off. The fingerprint decides
 * whether a firing becomes the next EPISODE of an existing alert — one row, the
 * Actions cell going from "Resolved" back to actionable, a "9th" recurrence badge —
 * or a brand-new alert with its own history.</p>
 *
 * <p>It used to include {@code streamName}, and this deployment's template emits
 * {@code stream} on some firings and not others. Verified live:
 * {@code k8s-pod-cpu-disaster} for the same pod existed twice, once with a null
 * stream name and once with {@code k8s_pod_cpu_limit_utilization}. Two identities
 * for one problem — a duplicate row that collapsing cannot merge, and an alert whose
 * history restarts instead of continuing.</p>
 */
class AlertStreamRowMapperIdentityTest {

    private static final String ORG = "default";
    private static final AlertIngestProperties.Mapping MAPPING = new AlertIngestProperties.Mapping();
    private static final int MAX_PAYLOAD = 8_000;

    /** A firing, optionally carrying the `stream` field the template is inconsistent about. */
    private static Map<String, Object> row(String alertName, String stream, String endpoint) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("_timestamp", 1786359896800437L);
        row.put("alertname", alertName);
        row.put("endpoint", endpoint);
        row.put("severity", "Warning");
        if (stream != null) {
            row.put("stream", stream);
        }
        return row;
    }

    private static String fingerprint(Map<String, Object> row, List<String> identityFields) {
        AlertEvent event = AlertStreamRowMapper.map(row, ORG, MAPPING, MAX_PAYLOAD, identityFields);
        return event.getFingerprint();
    }

    private static String withDefaultIdentity(Map<String, Object> row) {
        return fingerprint(row, AlertStreamRowMapper.DEFAULT_IDENTITY_FIELDS);
    }

    // ── the bug ───────────────────────────────────────────────────────────────

    /**
     * The regression that produced duplicate rows.
     *
     * <p>Two firings of one rule for one pod, differing only in whether the template
     * happened to include the stream name. Under the old identity these were two
     * alerts; they must be one.</p>
     */
    @Test
    void aTemplateThatSometimesOmitsTheStreamStillYieldsOneAlert() {
        String withStream = withDefaultIdentity(
                row("k8s-pod-cpu-disaster", "k8s_pod_cpu_limit_utilization", "dev/tiotesb-1"));
        String withoutStream = withDefaultIdentity(
                row("k8s-pod-cpu-disaster", null, "dev/tiotesb-1"));

        assertEquals(withoutStream, withStream,
                "the same alert split into two identities because the template's `stream` "
                        + "field is not always present");
    }

    /**
     * The regression that produced a NEW alert row on every single poll.
     *
     * <p>Identity was briefly computed from every column the stream row carried.
     * That reads as a stronger identity and is in fact none at all: the measured
     * value, the match count and both window bounds change on every evaluation, so
     * one rule firing every 30 seconds minted a fingerprint nothing had ever seen.
     * findOpenEpisode matched nothing, a fresh episode opened each time, and the
     * screen filled with copies of one problem that had never gone away.</p>
     *
     * <p>Two evaluations of one rule, ten minutes apart, with the numbers that
     * genuinely move between them. Same alert.</p>
     */
    @Test
    void aRuleThatKeepsFiringStaysOneAlertWhileItsNumbersMove() {
        Map<String, Object> firstEvaluation = new LinkedHashMap<>();
        firstEvaluation.put("_timestamp", 1786359896800437L);
        firstEvaluation.put("alertname", "K8s Pod High Memory Usage");
        firstEvaluation.put("endpoint", "dev/kafka1");
        firstEvaluation.put("severity", "Warning");
        firstEvaluation.put("alert_count", "6");
        firstEvaluation.put("alert_agg_value", "82.7");
        firstEvaluation.put("alert_start_time", "1786359296800437");
        firstEvaluation.put("alert_end_time", "1786359896800437");

        Map<String, Object> tenMinutesLater = new LinkedHashMap<>(firstEvaluation);
        tenMinutesLater.put("_timestamp", 1786360496800437L);
        tenMinutesLater.put("alert_count", "9");
        tenMinutesLater.put("alert_agg_value", "88.1");
        tenMinutesLater.put("alert_start_time", "1786359896800437");
        tenMinutesLater.put("alert_end_time", "1786360496800437");

        assertEquals(withDefaultIdentity(firstEvaluation), withDefaultIdentity(tenMinutesLater),
                "the same rule still firing must fold into its open episode, not open a "
                        + "new alert every time the value it measures changes");
    }

    /** And that is specifically what including streamName used to do. */
    @Test
    void includingTheStreamNameIsWhatSplitThem() {
        List<String> old = List.of("org", "alertName", "streamType", "streamName");

        assertNotEquals(
                fingerprint(row("k8s-pod-cpu-disaster", "k8s_pod_cpu_limit_utilization", "p"), old),
                fingerprint(row("k8s-pod-cpu-disaster", null, "p"), old),
                "this is the old behaviour being reproduced, so the fix is not a coincidence");
    }

    // ── identity is still an identity ─────────────────────────────────────────

    @Test
    void differentRulesRemainDifferentAlerts() {
        assertNotEquals(
                withDefaultIdentity(row("k8s-pod-cpu-disaster", null, "p")),
                withDefaultIdentity(row("k8s-pod-memory-disaster", null, "p")));
    }

    /**
     * Two rules that differ ONLY by severity level are one incident.
     *
     * <p>This is the case the identity is built around. OpenObserve has no severity
     * concept, so expressing levels as one rule per level is a normal pattern and this
     * deployment uses it. Under the old identity these were two alerts, and a condition
     * that got worse read as a warning that mysteriously stopped plus an unrelated
     * disaster that appeared — the escalation, which is the single most important thing
     * about it, was invisible.</p>
     *
     * <p>They share an {@code incidentType}, so the second firing folds into the open
     * episode and takes it to the higher severity. See
     * {@code AlertProcessingServiceImpl.applySeverity}.</p>
     */
    @Test
    void twoSeverityLevelsOfOneConditionAreOneAlert() {
        assertEquals(
                withDefaultIdentity(row("k8s-pod-cpu-warning", null, "dev/tiotesb-1")),
                withDefaultIdentity(row("k8s-pod-cpu-disaster", null, "dev/tiotesb-1")),
                "a condition getting worse must escalate ONE incident, not open a second");
    }

    /**
     * Two services breaching the same rule are two incidents.
     *
     * <p>REVERSED DELIBERATELY. This previously asserted the opposite — that one rule
     * across two entities was a single alert — on the reasoning that a rule reporting a
     * list of affected pods should not become a row per pod.</p>
     *
     * <p>That reasoning does not survive contact with the requirement it was written
     * before: an incident is "this service, this thing wrong with it", and one service's
     * CPU problem is not the same operational event as another's. Merging them gives one
     * row whose Service column names whichever host the query happened to order first,
     * and acknowledging it claims ownership of a problem on a machine nobody looked at.</p>
     *
     * <p>The multi-entity case the old test was protecting is still handled, and still
     * without duplication: a single firing that reports several entities is ONE event, so
     * it remains one row, with {@code affectedCount} and the entities list describing the
     * spread. What splits here is separate firings that name different services.</p>
     */
    @Test
    void twoServicesBreachingOneRuleAreTwoAlerts() {
        assertNotEquals(
                withDefaultIdentity(row("K8s Pod High Memory Usage", null, "dev/kafka1")),
                withDefaultIdentity(row("K8s Pod High Memory Usage", null, "dev/kafka2")),
                "one service's problem is not the same incident as another's");
    }

    /** Tenants never share an identity, whatever else matches. */
    @Test
    void twoOrganizationsNeverShareAnAlert() {
        Map<String, Object> r = row("K8s Pod High Memory Usage", null, "dev/kafka1");
        AlertEvent a = AlertStreamRowMapper.map(r, "default", MAPPING, MAX_PAYLOAD,
                AlertStreamRowMapper.DEFAULT_IDENTITY_FIELDS);
        AlertEvent b = AlertStreamRowMapper.map(r, "other", MAPPING, MAX_PAYLOAD,
                AlertStreamRowMapper.DEFAULT_IDENTITY_FIELDS);

        assertNotEquals(a.getFingerprint(), b.getFingerprint());
    }

    // ── configurability ───────────────────────────────────────────────────────

    /** A deployment whose template is reliable can put the stream back in. */
    @Test
    void identityFieldsAreConfigurable() {
        List<String> withEntity = List.of("org", "alertName", "entities");

        assertNotEquals(
                fingerprint(row("K8s Pod High Memory Usage", null, "dev/kafka1"), withEntity),
                fingerprint(row("K8s Pod High Memory Usage", null, "dev/kafka2"), withEntity));
    }

    /** The 4-arg overload other callers use keeps working, on the default identity. */
    @Test
    void theLegacyOverloadUsesTheDefaultIdentity() {
        Map<String, Object> r = row("k8s-pod-cpu-disaster", "some_stream", "p");

        assertEquals(withDefaultIdentity(r),
                AlertStreamRowMapper.map(r, ORG, MAPPING, MAX_PAYLOAD).getFingerprint());
    }

    /** Order matters, and parts cannot collide by straddling a boundary. */
    @Test
    void partsCannotCollideAcrossTheirBoundary() {
        assertNotEquals(
                AlertPayloadNormalizer.fingerprintOf(List.of("ab", "c")),
                AlertPayloadNormalizer.fingerprintOf(List.of("a", "bc")));
    }
}
