package com.trinity.observability.service.incident;

import com.trinity.observability.entity.incident.OoIncidentEntity;

public interface OoIncidentWebhookService {
    OoIncidentEntity processWebhook(String payload);
}
