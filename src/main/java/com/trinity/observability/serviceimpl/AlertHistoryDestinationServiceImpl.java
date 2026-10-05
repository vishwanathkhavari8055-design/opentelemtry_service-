package com.trinity.observability.serviceimpl;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.AlertIngestProperties;
import com.trinity.observability.config.AlertRuleSyncProperties;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.service.AlertHistoryDestinationService;

/**
 * Creates and maintains the OpenObserve template + destination that route alert
 * firings into the stream this service polls.
 *
 * <h2>The template's field names are the ingest contract</h2>
 *
 * <p>The body below renders exactly the keys {@code AlertIngestProperties.Mapping}
 * reads by default, so a rule created through this application is mapped
 * completely with no configuration at all. A deployment whose alerts come from a
 * hand-written template instead maps them by editing
 * {@code alerts.ingest.mapping.fields.*} — which is why the mapping is
 * configuration rather than these key names being hard-coded in the mapper.</p>
 *
 * <h2>Authentication</h2>
 *
 * <p>The destination targets OpenObserve's own ingest API, which is authenticated,
 * so the configured {@code openobserve.auth-token} is attached as a header. That
 * token is already the credential this service uses for every other call, so no
 * new secret is introduced — but it does mean the token is stored inside an
 * OpenObserve destination, readable by anyone who can administer OpenObserve.
 * That is the same trust boundary as the credential itself, and it is called out
 * here so the decision is visible rather than implicit.</p>
 */
@Service
public class AlertHistoryDestinationServiceImpl implements AlertHistoryDestinationService {

    private static final Logger log = LogManager.getLogger(AlertHistoryDestinationServiceImpl.class);

    /** OpenObserve names appear in URL paths, so they are validated rather than escaped. */
    private static final String API_PREFIX = "/api/";

    private static final Pattern NAME_RE = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /**
     * The notification body OpenObserve will POST into the stream.
     *
     * <p>Carried over verbatim from the webhook implementation: the field names were
     * already chosen to match what this service reads, and the only thing that
     * changed is where the destination points.</p>
     */
    private static final String TEMPLATE_BODY = """
            {
              "source": "openobserve",
              "org_name": "{org_name}",
              "alert_name": "{alert_name}",
              "alert_type": "{alert_type}",
              "stream_type": "{stream_type}",
              "stream_name": "{stream_name}",
              "alert_period": "{alert_period}",
              "alert_operator": "{alert_operator}",
              "alert_threshold": "{alert_threshold}",
              "alert_count": "{alert_count}",
              "alert_agg_value": "{alert_agg_value}",
              "alert_start_time": "{alert_start_time}",
              "alert_end_time": "{alert_end_time}",
              "alert_url": "{alert_url}"
            }""";

    private final OpenObserveClientService openObserve;
    private final OpenObserveConfig openObserveConfig;
    private final AlertIngestProperties ingestProperties;
    private final AlertRuleSyncProperties syncProperties;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Orgs already provisioned in this JVM's lifetime.
     *
     * <p>Provisioning is idempotent but not free — it lists templates and
     * destinations, so two OpenObserve calls. This runs before every rule push, and
     * a rule editor that saves five rules should not make ten redundant calls. A
     * restart re-checks, which is the right cadence for something that only changes
     * when somebody edits OpenObserve by hand.</p>
     */
    private final ConcurrentHashMap<String, Boolean> provisioned = new ConcurrentHashMap<>();

    @Value("${openobserve.auth-token:}")
    private String authToken;

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    public AlertHistoryDestinationServiceImpl(OpenObserveClientService openObserve,
            OpenObserveConfig openObserveConfig,
            AlertIngestProperties ingestProperties,
            AlertRuleSyncProperties syncProperties) {
        this.openObserve = openObserve;
        this.openObserveConfig = openObserveConfig;
        this.ingestProperties = ingestProperties;
        this.syncProperties = syncProperties;
    }

    @Override
    public boolean isEnabled() {
        return openObserveEnabled && syncProperties.isAutoAttachHistoryDestination();
    }

    @Override
    public String destinationName() {
        return syncProperties.getHistoryDestinationName();
    }

    @Override
    public Provisioned ensureProvisioned(String org) {
        String destination = syncProperties.getHistoryDestinationName();
        String template = syncProperties.getHistoryTemplateName();
        String url = ingestUrl(org);

        if (!isEnabled()) {
            return new Provisioned(template, destination, url, false, false,
                    "automatic history-destination provisioning is disabled");
        }

        if (Boolean.TRUE.equals(provisioned.get(org))) {
            return new Provisioned(template, destination, url, false, false, null);
        }

        try {
            validName(template, "history template name");
            validName(destination, "history destination name");

            boolean templateCreated = upsertTemplate(org, template);
            boolean destinationCreated = upsertDestination(org, destination, url, template);

            provisioned.put(org, Boolean.TRUE);
            if (templateCreated || destinationCreated) {
                log.info("Alert history wiring provisioned in org '{}': template '{}', "
                        + "destination '{}' -> {}", org, template, destination, url);
            }
            return new Provisioned(template, destination, url,
                    templateCreated, destinationCreated, null);
        } catch (Exception e) {
            // Never fatal. A rule that saves but is not wired for history still
            // evaluates and still notifies whatever else it is attached to; the
            // consequence is that it will not appear in THIS UI, which is worth a
            // loud log and a reported error, not a failed save.
            log.error("Could not provision the alert history destination in org '{}' — rules will "
                    + "evaluate but their firings may not reach the Alerts screen: {}",
                    org, e.getMessage());
            return new Provisioned(template, destination, url, false, false, e.getMessage());
        }
    }

    /**
     * OpenObserve's own JSON ingest endpoint for the alert stream.
     *
     * <p>Built from {@code openobserve.host} — the address as configured for this
     * service — because that is also the address OpenObserve reaches itself on in
     * every deployment observed here. A deployment where the two genuinely differ
     * sets {@code alerts.rule-sync.history-destination-url} explicitly.</p>
     */
    private String ingestUrl(String org) {
        String override = syncProperties.getHistoryDestinationUrl();
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        return openObserveConfig.getHost() + API_PREFIX + org + "/"
                + ingestProperties.getStream() + "/_json";
    }

    /** @return true when the template was created rather than updated */
    private boolean upsertTemplate(String org, String name) {
        ObjectNode body = json.createObjectNode();
        body.put("name", name);
        body.put("body", TEMPLATE_BODY);
        body.put("type", "http");
        body.put("isDefault", false);

        if (existingNames(API_PREFIX + org + "/alerts/templates").contains(name)) {
            openObserve.sendJson("PUT", API_PREFIX + org + "/alerts/templates/" + name, body);
            return false;
        }
        openObserve.sendJson("POST", API_PREFIX + org + "/alerts/templates", body);
        return true;
    }

    /** @return true when the destination was created rather than updated */
    private boolean upsertDestination(String org, String name, String url, String templateName) {
        ObjectNode body = json.createObjectNode();
        body.put("name", name);
        body.put("url", url);
        body.put("method", "post");
        body.put("skip_tls_verify", syncProperties.isHistoryDestinationSkipTlsVerify());
        body.put("template", templateName);
        // Without an explicit type OpenObserve creates a PIPELINE destination,
        // which never appears in the alert editor and silently cannot be attached.
        body.put("type", "http");
        if (authToken != null && !authToken.isBlank()) {
            // The ingest endpoint is authenticated. This is the same credential the
            // service already uses — see the class note on where it ends up.
            body.putObject("headers").put("Authorization", authToken);
        }

        if (existingNames(API_PREFIX + org + "/alerts/destinations").contains(name)) {
            openObserve.sendJson("PUT", API_PREFIX + org + "/alerts/destinations/" + name, body);
            return false;
        }
        openObserve.sendJson("POST", API_PREFIX + org + "/alerts/destinations", body);
        return true;
    }

    /**
     * Names already present at an OpenObserve collection endpoint.
     *
     * <p>Tolerates both envelope shapes OpenObserve uses — a bare array and a
     * {@code {"list": [...]}} wrapper — because which one it returns has varied
     * between versions of the same endpoint.</p>
     */
    private List<String> existingNames(String path) {
        List<String> names = new ArrayList<>();
        JsonNode root = openObserve.getJson(path);
        JsonNode array = root.isArray() ? root : root.path("list");
        if (array.isArray()) {
            for (JsonNode entry : array) {
                String name = entry.path("name").asText(null);
                if (name != null && !name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static void validName(String value, String what) {
        if (value == null || !NAME_RE.matcher(value).matches()) {
            throw new IllegalArgumentException(what + " '" + value
                    + "' is invalid (expected " + NAME_RE.pattern() + ")");
        }
    }
}
