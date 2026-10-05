package com.trinity.observability.registry;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON-file registry — what runs when this deployment has no database.
 *
 * <p>Everything is held in memory and the file is rewritten on every change. That
 * is the right shape for this data and not a shortcut: the registry is tens of
 * rows of metadata, read on every screen load and written only when an
 * administrator registers something, so a read must never touch the disk and a
 * write can afford to rewrite the lot.</p>
 *
 * <h2>Writes are atomic</h2>
 *
 * <p>Serialise to a sibling {@code .tmp} and move it into place, so a crash or a
 * full disk mid-write leaves the previous registry intact rather than a truncated
 * file that loads as an empty catalog. {@link AtomicMoveNotSupportedException} is
 * caught because an atomic move is not guaranteed across every filesystem —
 * losing atomicity is better than losing the write.</p>
 *
 * <h2>A corrupt file does not stop the service</h2>
 *
 * <p>An unreadable registry is logged and treated as empty. The alternative — a
 * failed startup — takes Logs, Traces, Metrics and Alerts down over a file that
 * only this one screen depends on.</p>
 */
public class FileDashboardRegistryStore implements DashboardRegistryStore {

    private static final Logger log = LogManager.getLogger(FileDashboardRegistryStore.class);

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path file;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** scopeKey -> uid -> registration. Insertion-ordered so the file stays readable. */
    private final Map<String, Map<String, DashboardRegistration>> byScope = new LinkedHashMap<>();

    public FileDashboardRegistryStore(String path) {
        this.file = Path.of(path).toAbsolutePath();
        load();
    }

    @Override
    public List<DashboardRegistration> list(String scopeKey) {
        lock.readLock().lock();
        try {
            Map<String, DashboardRegistration> scope = byScope.get(scopeKey);
            return scope == null ? List.of() : new ArrayList<>(scope.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Optional<DashboardRegistration> find(String scopeKey, String uid) {
        lock.readLock().lock();
        try {
            Map<String, DashboardRegistration> scope = byScope.get(scopeKey);
            return Optional.ofNullable(scope == null ? null : scope.get(uid));
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public DashboardRegistration save(DashboardRegistration registration) {
        lock.writeLock().lock();
        try {
            byScope.computeIfAbsent(registration.scopeKey(), k -> new LinkedHashMap<>())
                    .put(registration.uid(), registration);
            persist();
            return registration;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean delete(String scopeKey, String uid) {
        lock.writeLock().lock();
        try {
            Map<String, DashboardRegistration> scope = byScope.get(scopeKey);
            boolean removed = scope != null && scope.remove(uid) != null;
            if (removed) {
                if (scope.isEmpty()) {
                    byScope.remove(scopeKey);
                }
                persist();
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public String describe() {
        return "JSON file (" + file + ")";
    }

    // ── persistence ─────────────────────────────────────────────────────────

    private void load() {
        if (!Files.exists(file)) {
            log.info("Dashboard registry file {} does not exist yet — starting empty", file);
            return;
        }
        try {
            byte[] raw = Files.readAllBytes(file);
            if (raw.length == 0) {
                return;
            }
            List<Row> rows = mapper.readValue(raw, new TypeReference<List<Row>>() { });
            int loaded = 0;
            for (Row row : rows) {
                if (row == null || row.uid == null || row.uid.isBlank()) {
                    continue;
                }
                DashboardRegistration reg = row.toModel();
                byScope.computeIfAbsent(reg.scopeKey(), k -> new LinkedHashMap<>())
                        .put(reg.uid(), reg);
                loaded++;
            }
            log.info("Dashboard registry: loaded {} registration(s) from {}", loaded, file);
        } catch (Exception e) {
            // See the class note: an unreadable registry costs this one screen, and
            // failing startup over it would cost every other one.
            log.error("Dashboard registry file {} could not be read — continuing with an empty "
                    + "registry. Fix or delete the file to clear this.", file, e);
        }
    }

    private void persist() {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<Row> rows = new ArrayList<>();
            for (Map<String, DashboardRegistration> scope : byScope.values()) {
                for (DashboardRegistration reg : scope.values()) {
                    rows.add(Row.of(reg));
                }
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), rows);
            atomicOrReplaceMove(tmp, file);
        } catch (IOException e) {
            // A write that failed MUST surface: the caller has just been told its
            // registration succeeded, and the in-memory map now disagrees with disk.
            throw new IllegalStateException(
                    "Could not write the dashboard registry to " + file, e);
        }
    }

    private static void atomicOrReplaceMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * The on-disk shape.
     *
     * <p>A mutable POJO rather than the record, so an older or newer file with
     * different fields still loads: unknown properties are ignored and missing ones
     * take their defaults. Hand-editing this file is a legitimate way to seed a
     * deployment, and a strict binding would punish a stray key.</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Row {
        private String scopeKey = DashboardRegistration.GLOBAL_SCOPE;
        private String uid;
        private String title = "";
        private String folderUid = "";
        private String folderTitle = "";
        private String grafanaFolderUid;
        private String grafanaFolderTitle;
        private boolean enabled = true;
        private int displayOrder;
        private Instant registeredAt;
        private Instant updatedAt;

        public String getScopeKey() {
            return scopeKey;
        }

        public void setScopeKey(String scopeKey) {
            this.scopeKey = scopeKey;
        }

        public String getUid() {
            return uid;
        }

        public void setUid(String uid) {
            this.uid = uid;
        }

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }

        public String getFolderUid() {
            return folderUid;
        }

        public void setFolderUid(String folderUid) {
            this.folderUid = folderUid;
        }

        public String getFolderTitle() {
            return folderTitle;
        }

        public void setFolderTitle(String folderTitle) {
            this.folderTitle = folderTitle;
        }

        public String getGrafanaFolderUid() {
            return grafanaFolderUid;
        }

        public void setGrafanaFolderUid(String grafanaFolderUid) {
            this.grafanaFolderUid = grafanaFolderUid;
        }

        public String getGrafanaFolderTitle() {
            return grafanaFolderTitle;
        }

        public void setGrafanaFolderTitle(String grafanaFolderTitle) {
            this.grafanaFolderTitle = grafanaFolderTitle;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getDisplayOrder() {
            return displayOrder;
        }

        public void setDisplayOrder(int displayOrder) {
            this.displayOrder = displayOrder;
        }

        public Instant getRegisteredAt() {
            return registeredAt;
        }

        public void setRegisteredAt(Instant registeredAt) {
            this.registeredAt = registeredAt;
        }

        public Instant getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(Instant updatedAt) {
            this.updatedAt = updatedAt;
        }

        static Row of(DashboardRegistration reg) {
            Row row = new Row();
            row.scopeKey = reg.scopeKey();
            row.uid = reg.uid();
            row.title = reg.title();
            row.folderUid = reg.folderUid();
            row.folderTitle = reg.folderTitle();
            row.grafanaFolderUid = reg.grafanaFolderUid();
            row.grafanaFolderTitle = reg.grafanaFolderTitle();
            row.enabled = reg.enabled();
            row.displayOrder = reg.displayOrder();
            row.registeredAt = reg.registeredAt();
            row.updatedAt = reg.updatedAt();
            return row;
        }

        DashboardRegistration toModel() {
            Instant now = Instant.now();
            return new DashboardRegistration(
                    scopeKey == null || scopeKey.isBlank()
                            ? DashboardRegistration.GLOBAL_SCOPE : scopeKey,
                    uid,
                    title == null ? "" : title,
                    folderUid == null ? "" : folderUid,
                    folderTitle == null ? "" : folderTitle,
                    grafanaFolderUid,
                    grafanaFolderTitle,
                    enabled,
                    displayOrder,
                    registeredAt == null ? now : registeredAt,
                    updatedAt == null ? now : updatedAt);
        }
    }
}
