package com.trinity.observability.registry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.transaction.annotation.Transactional;

import com.trinity.observability.entity.GrafanaDashboardEntity;
import com.trinity.observability.repository.GrafanaDashboardRepository;

/**
 * PostgreSQL-backed registry — the durable store, and the default wherever this
 * deployment already has the alert/catalog datasource.
 *
 * <p>Not a {@code @Component}: it is constructed by {@code DashboardCatalogConfig}
 * only when the repository bean actually exists, so a deployment running with
 * {@code alerts.persistence.enabled=false} gets the file store instead of a
 * startup failure.</p>
 */
@Transactional
public class JpaDashboardRegistryStore implements DashboardRegistryStore {

    private final GrafanaDashboardRepository repository;

    public JpaDashboardRegistryStore(GrafanaDashboardRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DashboardRegistration> list(String scopeKey) {
        return repository.findByScopeKey(scopeKey).stream()
                .map(JpaDashboardRegistryStore::toModel)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DashboardRegistration> find(String scopeKey, String uid) {
        return repository.findByScopeKeyAndDashboardUid(scopeKey, uid)
                .map(JpaDashboardRegistryStore::toModel);
    }

    @Override
    public DashboardRegistration save(DashboardRegistration registration) {
        // Upsert on (scope, uid) rather than blind insert: a re-register or a sync
        // must land on the existing row, and the unique index would otherwise turn
        // an edit into a constraint violation.
        GrafanaDashboardEntity entity = repository
                .findByScopeKeyAndDashboardUid(registration.scopeKey(), registration.uid())
                .orElseGet(GrafanaDashboardEntity::new);

        entity.setScopeKey(registration.scopeKey());
        entity.setDashboardUid(registration.uid());
        entity.setTitle(nullToEmpty(registration.title()));
        entity.setFolderUid(nullToEmpty(registration.folderUid()));
        entity.setFolderTitle(nullToEmpty(registration.folderTitle()));
        entity.setGrafanaFolderUid(registration.grafanaFolderUid());
        entity.setGrafanaFolderTitle(registration.grafanaFolderTitle());
        entity.setEnabled(registration.enabled());
        entity.setDisplayOrder(registration.displayOrder());
        entity.setRegisteredAt(registration.registeredAt() == null
                ? Instant.now() : registration.registeredAt());
        entity.setUpdatedAt(registration.updatedAt() == null
                ? Instant.now() : registration.updatedAt());

        return toModel(repository.save(entity));
    }

    @Override
    public boolean delete(String scopeKey, String uid) {
        Optional<GrafanaDashboardEntity> existing =
                repository.findByScopeKeyAndDashboardUid(scopeKey, uid);
        existing.ifPresent(repository::delete);
        return existing.isPresent();
    }

    @Override
    public String describe() {
        return "PostgreSQL (grafana_dashboard_registrations)";
    }

    private static DashboardRegistration toModel(GrafanaDashboardEntity e) {
        return new DashboardRegistration(
                e.getScopeKey(),
                e.getDashboardUid(),
                e.getTitle(),
                e.getFolderUid(),
                e.getFolderTitle(),
                e.getGrafanaFolderUid(),
                e.getGrafanaFolderTitle(),
                e.isEnabled(),
                e.getDisplayOrder(),
                e.getRegisteredAt(),
                e.getUpdatedAt());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
