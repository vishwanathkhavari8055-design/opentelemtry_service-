package com.trinity.observability.controller.incident;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trinity.observability.entity.incident.OoIncidentEntity;
import com.trinity.observability.service.incident.OoIncidentWebhookService;

@RestController
@RequestMapping("/api/incidents")
public class OoIncidentWebhookController {

    private static final Logger log = LoggerFactory.getLogger(OoIncidentWebhookController.class);

    private final OoIncidentWebhookService ooIncidentWebhookService;

    public OoIncidentWebhookController(OoIncidentWebhookService ooIncidentWebhookService) {
        this.ooIncidentWebhookService = ooIncidentWebhookService;
    }

    @PostMapping("/webhook")
    public ResponseEntity<String> receiveWebhook(@RequestBody String payload) {
        log.info("[OO_INCIDENT_WEBHOOK] Received payload: {}", payload);
        try {
            OoIncidentEntity incident = ooIncidentWebhookService.processWebhook(payload);
            return ResponseEntity.ok("Successfully synced incident: " + incident.getOpenobserveIncidentId());
        } catch (Exception e) {
            log.error("[OO_INCIDENT_WEBHOOK_ERROR] Failed to process webhook: {}", e.getMessage());
            return ResponseEntity.internalServerError().body("Processing error: " + e.getMessage());
        }
    }
}
