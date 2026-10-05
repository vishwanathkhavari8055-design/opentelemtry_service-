package com.trinity.observability.controller.incident;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import com.trinity.observability.entity.incident.OoIncidentEntity;
import com.trinity.observability.repository.incident.OoIncidentRepository;

@RestController
@RequestMapping("/api/oo-incidents")
public class OoIncidentController {

    private final OoIncidentRepository ooIncidentRepo;

    public OoIncidentController(OoIncidentRepository ooIncidentRepo) {
        this.ooIncidentRepo = ooIncidentRepo;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<OoIncidentEntity> listIncidents() {
        List<OoIncidentEntity> incidents = ooIncidentRepo.findAll();
        // Note: N+1 — this touches each incident's lazy `alerts` collection, so a
        // findAll() of N incidents costs 1 + N queries. Invisible today only
        // because the table is small; it will not stay that way. Fix with a
        // fetch-join (`LEFT JOIN FETCH i.alerts`) or an @EntityGraph on the
        // repository method. Deliberately left as-is in the latency pass that
        // added this note — out of scope there, and worth its own change.
        for (OoIncidentEntity incident : incidents) {
            org.hibernate.Hibernate.initialize(incident.getAlerts());
        }
        return incidents;
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<OoIncidentEntity> getIncident(@PathVariable("id") UUID id) {
        return ooIncidentRepo.findById(id)
                .map(incident -> {
                    // Initialize lazy collections
                    org.hibernate.Hibernate.initialize(incident.getAlerts());
                    return ResponseEntity.ok(incident);
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
