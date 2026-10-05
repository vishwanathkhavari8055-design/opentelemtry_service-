package com.trinity.observability.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.ProductCatalogProperties;
import com.trinity.observability.dto.productcatalog.CatalogResource;
import com.trinity.observability.dto.productcatalog.ResourceCategory;
import com.trinity.observability.dto.productcatalog.ResourceStatus;
import com.trinity.observability.dto.productcatalog.TelemetryBinding;
import com.trinity.observability.util.OrgContext;
import com.trinity.observability.util.SqlSafety;

/**
 * Durability without a database: the registry's change log is an OpenObserve
 * stream.
 *
 * <p>This is the "non-database mechanism already used by the application" the
 * requirement asks for — it is exactly how the in-UI alert feed persists fired
 * alerts (see {@code AlertWebhookController} and
 * {@code OpenObserveClientService#ingest}). No table, no migration, no
 * datasource, and the catalog inherits OpenObserve's retention and backup story
 * rather than inventing its own.</p>
 *
 * <h2>Append-only, and what that forces</h2>
 *
 * <p>A stream cannot be updated in place or deleted by key, so every change is
 * an event and {@link #load(String)} replays them with last-write-wins per id. A
 * removal is a tombstone row, not an absence — an absence is indistinguishable
 * from "never registered", and the difference matters when the same name is
 * registered again later.</p>
 *
 * <h2>Every field is written as a string</h2>
 *
 * <p>Deliberate, and the reason is in {@code OpenObserveClientImpl.ingest}'s
 * javadoc: OpenObserve infers a stream's schema from the first write and REJECTS
 * later records whose types conflict — reporting the rejection inside a 200
 * response. A field that is a number for one resource and null for the next is
 * exactly that hazard. Strings make the schema unconditionally stable; the
 * parsing cost on replay is paid once per org.</p>
 */
public class OpenObserveCatalogSnapshotStore implements CatalogSnapshotStore {

    private static final Logger log = LogManager.getLogger(OpenObserveCatalogSnapshotStore.class);

    /**
     * Defensive ceiling on rows pulled back during replay. A catalog of a few
     * hundred resources toggled daily for a year stays far below this; the bound
     * exists so a runaway writer cannot turn startup into an OOM.
     */
    private static final int MAX_CHANGE_LOG_ROWS = 20_000;
    private static final String FIELD_CATEGORY = "category";
    private static final String FIELD_UPDATED_AT_MICROS = "updated_at_micros";

    private final OpenObserveClientService openObserve;
    private final ProductCatalogProperties properties;
    private final ObjectMapper json = new ObjectMapper();

    public OpenObserveCatalogSnapshotStore(OpenObserveClientService openObserve,
            ProductCatalogProperties properties) {
        this.openObserve = openObserve;
        this.properties = properties;
    }

    @Override
    public List<CatalogResource> load(String org) {
        String stream = SqlSafety.validateIdentifier(properties.getPersistence().getStream());
        long endMicros = com.trinity.observability.util.TimeRangeParser.defaultEndMicros();
        long startMicros = endMicros
                - properties.getPersistence().getReplayDays() * 86_400_000_000L;

        // Ordered oldest-first so a straightforward last-write-wins fold works.
        // `catalog_id IS NOT NULL` also screens out any unrelated rows should the
        // stream name ever be reused.
        String sql = "SELECT * FROM \"" + stream + "\" "
                + "WHERE catalog_id IS NOT NULL AND catalog_id != '' "
                + "ORDER BY _timestamp ASC";

        OpenObserveClientService.SearchOutcome outcome;
        String previousOrg = OrgContext.get();
        try {
            scopeTo(org);
            outcome = openObserve.searchDetailed(sql, startMicros, endMicros, 0,
                    MAX_CHANGE_LOG_ROWS, "logs");
        } catch (Exception e) {
            // The overwhelmingly common cause is "the stream does not exist yet",
            // i.e. nothing has ever been registered. Debug, not error: a fresh
            // deployment should not log a stack trace on its first catalog read.
            log.debug("Product Catalog change log unavailable for org '{}' (stream '{}'): {}",
                    org, stream, e.getMessage());
            return List.of();
        } finally {
            restore(previousOrg);
        }

        // A partial read is the one answer that must NOT be folded. It looks like
        // an empty change log, and an empty change log means "nothing was ever
        // registered" — so accepting it silently unregisters the entire catalog,
        // and the registry then caches that as the truth. Raise instead, so the
        // registry keeps whatever it has and retries. The window is replay-days
        // wide by design, which is exactly the shape of query this instance
        // refuses once a stream spans enough parquet files.
        if (outcome.partial()) {
            throw new IllegalStateException("OpenObserve could not serve the Product Catalog "
                    + "change log for org '" + org + "' (stream '" + stream + "', last "
                    + properties.getPersistence().getReplayDays() + "d): "
                    + (outcome.error() == null ? "partial result" : outcome.error())
                    + ". Refusing to treat this as an empty catalog.");
        }

        return fold(outcome.hits(), org);
    }

    @Override
    public void recordChange(String org, CatalogResource resource, boolean deleted) {
        if (resource == null || resource.getId() == null || resource.getId().isBlank()) {
            return;
        }
        String stream = properties.getPersistence().getStream();
        ArrayNode records = json.createArrayNode();
        records.add(encode(resource, deleted));

        try {
            // ingest() takes the org explicitly, so no OrgContext juggling here —
            // and it raises on per-record rejection rather than reporting success.
            openObserve.ingest(effectiveOrg(org), stream, records);
            log.debug("Product Catalog change appended: org={}, id={}, deleted={}",
                    org, resource.getId(), deleted);
        } catch (Exception e) {
            // Per the CatalogSnapshotStore contract: never throw. The registry is
            // still correct in memory; only the restart-survival of THIS change
            // is lost, and saying so loudly is more useful than failing the
            // operator's click.
            log.error("Product Catalog change NOT persisted (org={}, id={}, deleted={}) — "
                    + "the registration is live but will not survive a restart: {}",
                    org, resource.getId(), deleted, e.getMessage());
        }
    }

    @Override
    public String describe() {
        return "OpenObserve stream '" + properties.getPersistence().getStream()
                + "' (append-only change log, replayed on first read)";
    }

    // ── encode / decode ──────────────────────────────────────────────────────

    private ObjectNode encode(CatalogResource resource, boolean deleted) {
        ObjectNode node = json.createObjectNode();
        node.put("catalog_id", resource.getId());
        node.put("catalog_org", nullToEmpty(resource.getOrg()));
        node.put(FIELD_CATEGORY, resource.getCategory() == null ? "" : resource.getCategory().name());
        node.put("resource_name", nullToEmpty(resource.getResourceName()));
        node.put("resolved_name", nullToEmpty(resource.getResolvedName()));
        node.put("status", resource.getStatus() == null ? ResourceStatus.ENABLED.name()
                : resource.getStatus().name());
        node.put("source", resource.getSource() == null ? CatalogResource.Source.MANUAL.name()
                : resource.getSource().name());
        node.put("bindings", encodeBindings(resource.getBindings()));
        node.put("signals", String.join(",", resource.getSignals()));
        // Numbers as strings — see the class note on schema stability.
        node.put("last_seen_micros", resource.getLastSeenMicros() == null ? ""
                : Long.toString(resource.getLastSeenMicros()));
        node.put("registered_at_micros", Long.toString(resource.getRegisteredAtMicros()));
        node.put(FIELD_UPDATED_AT_MICROS, Long.toString(resource.getUpdatedAtMicros()));
        node.put("deleted", Boolean.toString(deleted));
        return node;
    }

    /** Bindings joined with a newline, which no identifier value may contain. */
    private static String encodeBindings(List<TelemetryBinding> bindings) {
        if (bindings == null || bindings.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (TelemetryBinding binding : bindings) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(binding.encode());
        }
        return out.toString();
    }

    /**
     * Replay the change log into the surviving set.
     *
     * <p>Rows arrive oldest-first, but {@code updated_at_micros} is compared as
     * well rather than trusting arrival order alone: two changes inside the same
     * ingest microsecond would otherwise resolve arbitrarily, and an
     * enable-then-disable pair landing the wrong way round is a resource that
     * silently reappears in Logs.</p>
     */
    private List<CatalogResource> fold(List<Map<String, Object>> rows, String org) {
        ReplayState state = new ReplayState();
        int skipped = 0;

        for (Map<String, Object> row : rows) {
            if (!replay(row, org, state)) {
                skipped++;
            }
        }

        if (skipped > 0) {
            log.warn("Product Catalog replay skipped {} unreadable change-log row(s) for org '{}'",
                    skipped, org);
        }
        return new ArrayList<>(state.alive.values());
    }

    /**
     * Applies one change-log row to the replay state.
     *
     * @return false when the row is unreadable (no id, or an undecodable resource)
     */
    private boolean replay(Map<String, Object> row, String org, ReplayState state) {
        String id = str(row.get("catalog_id"));
        if (id == null || id.isBlank()) {
            return false;
        }
        long version = parseLong(str(row.get(FIELD_UPDATED_AT_MICROS)), 0L);
        boolean deleted = Boolean.parseBoolean(str(row.get("deleted")));
        if (isSuperseded(state, id, version, deleted)) {
            return true;
        }
        state.versionOf.put(id, version);

        if (deleted) {
            state.alive.remove(id);
            state.tombstoned.add(id);
            return true;
        }
        CatalogResource decoded = decode(id, row, org);
        if (decoded == null) {
            return false;
        }
        // A strictly newer row for a previously tombstoned id is a genuine
        // re-registration of the same id, so the tombstone no longer applies.
        state.tombstoned.remove(id);
        state.alive.put(id, decoded);
        return true;
    }

    /**
     * Whether a row at {@code version} loses to what the replay already holds for
     * {@code id}: an older row, or a same-version live row against a tombstone.
     */
    private static boolean isSuperseded(ReplayState state, String id, long version, boolean deleted) {
        Long known = state.versionOf.get(id);
        if (known == null) {
            return false;
        }
        boolean stale = version < known;
        boolean tombstoneWins = version == known && !deleted && state.tombstoned.contains(id);
        return stale || tombstoneWins;
    }

    /** Mutable state of one change-log replay. */
    private static final class ReplayState {
        final Map<String, CatalogResource> alive = new LinkedHashMap<>();
        final Map<String, Long> versionOf = new LinkedHashMap<>();
        final Set<String> tombstoned = new LinkedHashSet<>();
    }

    private CatalogResource decode(String id, Map<String, Object> row, String org) {
        ResourceCategory category;
        try {
            category = ResourceCategory.fromCode(str(row.get(FIELD_CATEGORY)));
        } catch (IllegalArgumentException e) {
            // A category that no longer exists in the enum. Dropping the row is
            // right: filing it under a guess would attach the resource's
            // telemetry filter to the wrong part of the taxonomy.
            log.warn("Product Catalog replay dropped id={} — unknown category '{}'",
                    id, row.get(FIELD_CATEGORY));
            return null;
        }

        String resourceName = str(row.get("resource_name"));
        if (resourceName == null || resourceName.isBlank()) {
            return null;
        }

        CatalogResource resource = new CatalogResource();
        resource.setId(id);
        resource.setOrg(org);
        resource.setCategory(category);
        resource.setResourceName(resourceName);
        resource.setResolvedName(blankToNull(str(row.get("resolved_name"))));

        ResourceStatus status;
        try {
            status = ResourceStatus.fromCodeOrNull(str(row.get("status")));
        } catch (IllegalArgumentException e) {
            status = null;
        }
        resource.setStatus(status == null ? ResourceStatus.ENABLED : status);

        resource.setSource(decodeSource(str(row.get("source"))));
        resource.setBindings(decodeBindings(str(row.get("bindings"))));
        resource.setSignals(decodeSignals(str(row.get("signals"))));

        String lastSeen = str(row.get("last_seen_micros"));
        resource.setLastSeenMicros(
                (lastSeen == null || lastSeen.isBlank()) ? null : parseLong(lastSeen, 0L));

        resource.setRegisteredAtMicros(parseLong(str(row.get("registered_at_micros")), 0L));
        resource.setUpdatedAtMicros(parseLong(str(row.get(FIELD_UPDATED_AT_MICROS)), 0L));
        return resource;
    }

    private static CatalogResource.Source decodeSource(String raw) {
        if (raw != null) {
            for (CatalogResource.Source candidate : CatalogResource.Source.values()) {
                if (candidate.name().equalsIgnoreCase(raw.trim())) {
                    return candidate;
                }
            }
        }
        return CatalogResource.Source.MANUAL;
    }

    private static List<TelemetryBinding> decodeBindings(String raw) {
        List<TelemetryBinding> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            TelemetryBinding binding = TelemetryBinding.decode(line);
            if (binding != null) {
                out.add(binding);
            }
        }
        return out;
    }

    private static Set<String> decodeSignals(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    // ── org scoping ──────────────────────────────────────────────────────────

    /**
     * Point {@code OpenObserveConfig.getOrg()} at the org being replayed.
     *
     * <p>{@code search} builds its URL from that method, which reads
     * {@link OrgContext}. Replay can run on a request thread (the first catalog
     * read of an org) or on a background one (the liveness refresher), so the
     * previous value is captured and restored — leaving a ThreadLocal pointing
     * at the wrong tenant is the failure mode {@code OrgContext} exists to
     * prevent.</p>
     */
    private static void scopeTo(String org) {
        if (OrgContext.isValid(org)) {
            OrgContext.set(org);
        } else {
            OrgContext.clear();
        }
    }

    private static void restore(String previousOrg) {
        if (previousOrg == null) {
            OrgContext.clear();
        } else {
            OrgContext.set(previousOrg);
        }
    }

    /** {@code ingest} needs a concrete org in the path; blank means the default. */
    private String effectiveOrg(String org) {
        if (OrgContext.isValid(org)) {
            return org;
        }
        String current = OrgContext.get();
        if (OrgContext.isValid(current)) {
            return current;
        }
        throw new IllegalStateException(
                "Product Catalog persistence needs an organization; none was supplied or in context");
    }

    // ── small helpers ────────────────────────────────────────────────────────

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static long parseLong(String value, long fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
