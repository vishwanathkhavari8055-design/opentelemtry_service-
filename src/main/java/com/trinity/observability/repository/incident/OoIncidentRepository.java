package com.trinity.observability.repository.incident;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.trinity.observability.entity.incident.OoIncidentEntity;

public interface OoIncidentRepository extends JpaRepository<OoIncidentEntity, UUID> {

    @Query("SELECT i FROM OoIncidentEntity i WHERE i.openobserveIncidentId = :openobserveIncidentId")
    Optional<OoIncidentEntity> findByOpenobserveIncidentId(@Param("openobserveIncidentId") String openobserveIncidentId);
}
