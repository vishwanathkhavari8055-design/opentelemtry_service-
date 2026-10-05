package com.trinity.observability.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class AlertTypeNormalizerTest {

    @Test
    void nullAndBlankYieldNull() {
        assertNull(AlertTypeNormalizer.incidentType(null));
        assertNull(AlertTypeNormalizer.incidentType("  "));
    }

    @Test
    void noStripReturnsTrimmedName() {
        assertEquals("k8s-pod-cpu-warning",
                AlertTypeNormalizer.incidentType(" k8s-pod-cpu-warning ", null, false));
    }

    @Test
    void stripsTrailingSeverityAfterSeparator() {
        List<String> ladder = Arrays.asList(null, " ", "Warning", "disaster", "critical");
        assertEquals("k8s-pod-cpu", AlertTypeNormalizer.incidentType("k8s-pod-cpu-WARNING", ladder, true));
        assertEquals("k8s-pod-cpu", AlertTypeNormalizer.incidentType("k8s-pod-cpu_disaster", ladder, true));
        assertEquals("Pod CPU", AlertTypeNormalizer.incidentType("Pod CPU critical", ladder, true));
        assertEquals("hyperwarning", AlertTypeNormalizer.incidentType("hyperwarning", ladder, true));
        assertEquals("critical", AlertTypeNormalizer.incidentType("critical", ladder, true));
        assertEquals("-critical", AlertTypeNormalizer.incidentType("-critical", ladder, true));
        assertEquals("disaster-recovery-check",
                AlertTypeNormalizer.incidentType("disaster-recovery-check", ladder, true));
        assertEquals("IoTOpsCIMService_high_cpu",
                AlertTypeNormalizer.incidentType("IoTOpsCIMService_high_cpu", ladder, true));
    }

    @Test
    void emptyLadderUsesDefault() {
        assertEquals(AlertTypeNormalizer.incidentType("svc-cpu-critical"),
                AlertTypeNormalizer.incidentType("svc-cpu-critical", List.of(), true));
    }
}
