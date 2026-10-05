package com.trinity.observability.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.trinity.observability.entity.GrafanaDashboardEntity;

/**
 * Rows of {@code grafana_dashboard_registrations}.
 *
 * <p>Derived queries only — the registry is a short list read whole, so there is
 * nothing here worth a hand-written JPQL query. Both methods are scope-keyed
 * because a query that forgot the scope would leak one tenant's dashboard list
 * into another's under {@code grafana.registry.scope=ORG}.</p>
 */
public interface GrafanaDashboardRepository extends JpaRepository<GrafanaDashboardEntity, Long> {

    List<GrafanaDashboardEntity> findByScopeKey(String scopeKey);

    Optional<GrafanaDashboardEntity> findByScopeKeyAndDashboardUid(String scopeKey, String uid);
}
