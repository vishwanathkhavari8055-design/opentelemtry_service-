package com.trinity.observability.serviceimpl.incident;

import java.io.File;
import java.io.FileWriter;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trinity.observability.entity.incident.OoIncidentEntity;
import com.trinity.observability.repository.incident.OoIncidentRepository;
import com.trinity.observability.service.incident.OoIncidentWebhookService;

@Service
public class OoIncidentWebhookServiceImpl implements OoIncidentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(OoIncidentWebhookServiceImpl.class);

    private final OoIncidentRepository ooIncidentRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public OoIncidentWebhookServiceImpl(OoIncidentRepository ooIncidentRepo) {
        this.ooIncidentRepo = ooIncidentRepo;
    }

    @Override
    @Transactional
    public OoIncidentEntity processWebhook(String payload) {
        // 1. Save raw payload to target/oo_last_incident_payload.json for inspection
        try {
            File targetDir = new File("target");
            if (!targetDir.exists()) {
                targetDir.mkdirs();
            }
            try (FileWriter writer = new FileWriter(new File(targetDir, "oo_last_incident_payload.json"))) {
                writer.write(payload);
            }
            log.info("[OO_INCIDENT_WEBHOOK] Saved raw payload to target/oo_last_incident_payload.json");
        } catch (Exception e) {
            log.error("[OO_INCIDENT_WEBHOOK] Failed to save raw payload to file: {}", e.getMessage());
        }

        // 2. Parse loosely as JsonNode to extract incident fields
        try {
            JsonNode root = mapper.readTree(payload);

            String ooIncidentId = root.path("incident_id").asText("");
            if (ooIncidentId.isEmpty()) {
                ooIncidentId = root.path("id").asText("");
            }

            if (ooIncidentId.isEmpty()) {
                throw new IllegalArgumentException("Webhook payload is missing incident ID");
            }

            String eventType = root.path("event_type").asText("UNKNOWN");
            String title = root.path("title").asText("OpenObserve Incident " + ooIncidentId);
            String description = root.path("description").asText("");
            String severity = root.path("severity").asText("Warning");
            String status = root.path("status").asText("OPEN");
            String ooUrl = root.path("url").asText("");

            java.util.Optional<OoIncidentEntity> existing = ooIncidentRepo.findByOpenobserveIncidentId(ooIncidentId);
            OoIncidentEntity incident;
            if (existing.isPresent()) {
                incident = existing.get();
            } else {
                incident = new OoIncidentEntity();
                incident.setOpenobserveIncidentId(ooIncidentId);
                incident.setCreatedAt(Instant.now());
            }

            incident.setEventType(eventType);
            incident.setTitle(title);
            incident.setDescription(description);
            incident.setSeverity(severity);
            incident.setStatus(status);
            incident.setOpenobserveUrl(ooUrl);
            incident.setRawPayload(payload);
            incident.setUpdatedAt(Instant.now());

            if ("RESOLVED".equalsIgnoreCase(status) || "CLOSED".equalsIgnoreCase(status)) {
                incident.setResolvedAt(Instant.now());
            }

            OoIncidentEntity saved = ooIncidentRepo.save(incident);
            log.info("[OO_INCIDENT_WEBHOOK_SAVED] Successfully synced incident {} -> {}", ooIncidentId, saved.getId());
            return saved;
        } catch (Exception e) {
            log.error("[OO_INCIDENT_WEBHOOK_ERROR] Failed to process webhook payload: {}", e.getMessage());
            throw new RuntimeException("Error processing webhook: " + e.getMessage(), e);
        }
    }
}
