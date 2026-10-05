package com.trinity.observability.serviceimpl;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trinity.observability.client.OpenObserveClientService;
import com.trinity.observability.config.OpenObserveConfig;
import com.trinity.observability.dto.iam.IamRequestDTO;
import com.trinity.observability.dto.iam.IamResponseDTO;
import com.trinity.observability.service.IamService;
import com.trinity.observability.util.TimeFormatter;

/**
 * Reads OpenObserve's identity endpoints:
 *
 * <pre>
 *   GET /api/organizations            → {"data":[{id,identifier,name,type,user_email,…}]}
 *   GET /api/{org}/users              → {"data":[{email,first_name,last_name,role,…}]}
 *   GET /api/{org}/service_accounts   → {"data":[…]}
 * </pre>
 *
 * <p>The org in the two scoped paths comes from
 * {@link OpenObserveConfig#getOrg()}, which resolves the per-request override —
 * so switching organization in the UI switches what these return, with no
 * argument threaded through.</p>
 */
@Service
public class IamServiceImpl implements IamService {

    private static final Logger log = LogManager.getLogger(IamServiceImpl.class);

    /** The email lands in a URL path segment, so it is validated, not just escaped. */
    private static final Pattern EMAIL_RE =
            Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}\\.[A-Za-z]{2,24}$");

    /**
     * Roles this service will set. Narrower than OpenObserve's own enum on
     * purpose: {@code service_account} would convert a person into a credential
     * holder, and {@code sre_agent} is an automation role. Neither is something
     * to hand to a dropdown.
     */
    private static final Set<String> ALLOWED_ROLES =
            Set.of("root", "admin", "editor", "viewer", "user");

    private static final String API_PREFIX = "/api/";
    private static final String FIELD_FIRST_NAME = "first_name";
    private static final String FIELD_LAST_NAME = "last_name";

    private final OpenObserveClientService oo;
    private final OpenObserveConfig config;
    private final ObjectMapper json = new ObjectMapper();

    @Value("${openobserve.enabled:false}")
    private boolean openObserveEnabled;

    public IamServiceImpl(OpenObserveClientService oo, OpenObserveConfig config) {
        this.oo = oo;
        this.config = config;
    }

    @Override
    public IamResponseDTO.Page<IamResponseDTO.Organization> listOrganizations() {
        IamResponseDTO.Page<IamResponseDTO.Organization> out = newPage(null);
        if (!openObserveEnabled) {
            log.info("listOrganizations() returning supported=false on ES path");
            return out;
        }
        out.setSupported(true);

        // Not org-scoped: this IS the list of orgs to choose between.
        for (JsonNode n : dataOf("/api/organizations", "organizations")) {
            IamResponseDTO.Organization o = new IamResponseDTO.Organization();
            o.setId(n.path("id").isNumber() ? n.path("id").asInt() : null);
            o.setIdentifier(text(n, "identifier"));
            o.setName(text(n, "name"));
            o.setType(text(n, "type"));
            o.setOwnerEmail(text(n, "user_email"));
            out.getItems().add(o);
        }
        log.info("listOrganizations() returning {} organization(s)", out.getItems().size());
        return out;
    }

    @Override
    public IamResponseDTO.Page<IamResponseDTO.User> listUsers() {
        String org = config.getOrg();
        IamResponseDTO.Page<IamResponseDTO.User> out = newPage(org);
        if (!openObserveEnabled) {
            log.info("listUsers() returning supported=false on ES path");
            return out;
        }
        out.setSupported(true);

        for (JsonNode n : dataOf(API_PREFIX + org + "/users", "users")) {
            IamResponseDTO.User u = new IamResponseDTO.User();
            u.setEmail(text(n, "email"));
            u.setFirstName(text(n, FIELD_FIRST_NAME));
            u.setLastName(text(n, FIELD_LAST_NAME));
            u.setRole(text(n, "role"));
            u.setExternal(n.path("is_external").isBoolean() ? n.path("is_external").asBoolean() : null);
            // OpenObserve stamps created_at in epoch MICROSECONDS, same unit as
            // _timestamp on the streams — not millis.
            if (n.path("created_at").isNumber()) {
                u.setCreatedAt(TimeFormatter.microsToIso(n.path("created_at").asLong()));
            }
            out.getItems().add(u);
        }
        log.info("listUsers() returning {} user(s) for org={}", out.getItems().size(), org);
        return out;
    }

    @Override
    public IamResponseDTO.Page<IamResponseDTO.ServiceAccount> listServiceAccounts() {
        String org = config.getOrg();
        IamResponseDTO.Page<IamResponseDTO.ServiceAccount> out = newPage(org);
        if (!openObserveEnabled) {
            log.info("listServiceAccounts() returning supported=false on ES path");
            return out;
        }
        out.setSupported(true);

        for (JsonNode n : dataOf(API_PREFIX + org + "/service_accounts", "service accounts")) {
            IamResponseDTO.ServiceAccount a = new IamResponseDTO.ServiceAccount();
            a.setEmail(text(n, "email"));
            a.setFirstName(text(n, FIELD_FIRST_NAME));
            a.setLastName(text(n, FIELD_LAST_NAME));
            a.setDescription(text(n, "description"));
            // Presence only — the token itself is never forwarded. See the
            // field's note in IamResponseDTO.
            a.setHasToken(n.hasNonNull("token") && !n.path("token").asText("").isEmpty());
            out.getItems().add(a);
        }
        log.info("listServiceAccounts() returning {} account(s) for org={}",
                out.getItems().size(), org);
        return out;
    }

    @Override
    public IamResponseDTO.WriteResult updateUser(String email, IamRequestDTO.UpdateUser update) {
        validateUpdate(email, update);

        String org = config.getOrg();
        ObjectNode body = json.createObjectNode();
        putProfileFields(body, update);

        boolean changing = Boolean.TRUE.equals(update.getChangePassword());
        body.put("change_password", changing);
        if (changing) {
            putPasswordChange(body, update);
        }

        String path = API_PREFIX + org + "/users/" + URLEncoder.encode(email, StandardCharsets.UTF_8);
        JsonNode res = oo.sendJson("PUT", path, body);

        IamResponseDTO.WriteResult out = new IamResponseDTO.WriteResult();
        out.setOk(true);
        out.setEmail(email);
        String message = res.path("message").asText(null);
        out.setMessage(message != null && !message.isEmpty() ? message : "User updated");
        // Deliberately does NOT log whether a password was set, only that a
        // credential change occurred — the fact is auditable, the secret is not
        // anywhere near the logs.
        log.info("updateUser({}) in org={} ok (role={}, passwordChanged={})",
                email, org, update.getRole(), changing);
        return out;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Rejects an update that cannot be sent: no backend, bad email, nothing to change or an unknown role. */
    private void validateUpdate(String email, IamRequestDTO.UpdateUser update) {
        if (!openObserveEnabled) {
            throw new IllegalStateException("Cannot update a user: IAM requires the OpenObserve backend.");
        }
        if (email == null || !EMAIL_RE.matcher(email).matches()) {
            throw new IllegalArgumentException("Invalid email: '" + email + "'");
        }
        if (update == null) {
            throw new IllegalArgumentException("updateUser: no changes supplied");
        }
        if (update.getRole() != null && !ALLOWED_ROLES.contains(update.getRole().toLowerCase())) {
            throw new IllegalArgumentException(
                    "Unsupported role: '" + update.getRole() + "'. Allowed: " + ALLOWED_ROLES);
        }
    }

    private static void putProfileFields(ObjectNode body, IamRequestDTO.UpdateUser update) {
        // Only what was actually supplied — OpenObserve treats an explicit null
        // as "clear this field", so sending unset fields would blank a name the
        // form never showed.
        if (update.getFirstName() != null) body.put(FIELD_FIRST_NAME, update.getFirstName());
        if (update.getLastName() != null)  body.put(FIELD_LAST_NAME, update.getLastName());
        if (update.getRole() != null)      body.put("role", update.getRole().toLowerCase());
    }

    private static void putPasswordChange(ObjectNode body, IamRequestDTO.UpdateUser update) {
        if (update.getNewPassword() == null || update.getNewPassword().isEmpty()) {
            throw new IllegalArgumentException(
                    "changePassword was requested but no new password was supplied");
        }
        body.put("new_password", update.getNewPassword());
        // Optional: OpenObserve requires it only when a user changes their
        // OWN password, which cannot happen over this shared service account.
        if (update.getOldPassword() != null) body.put("old_password", update.getOldPassword());
    }

    /**
     * GETs a path and returns its {@code data} array, or empty on failure.
     *
     * <p>A failure here degrades one IAM table to "no rows" and is logged; it
     * must not 500 the screen, because the three tables are fetched
     * independently and the other two may well be fine.</p>
     */
    private List<JsonNode> dataOf(String path, String what) {
        List<JsonNode> out = new ArrayList<>();
        try {
            JsonNode data = oo.getJson(path).path("data");
            if (data.isArray()) data.forEach(out::add);
            else log.warn("IAM {}: response had no 'data' array", what);
        } catch (Exception e) {
            log.error("IAM {} query failed: {}", what, e.getMessage(), e);
        }
        return out;
    }

    private <T> IamResponseDTO.Page<T> newPage(String org) {
        IamResponseDTO.Page<T> p = new IamResponseDTO.Page<>();
        p.setItems(new ArrayList<>());
        p.setOrg(org);
        p.setBackend(openObserveEnabled ? "openobserve" : "elasticsearch");
        return p;
    }

    private static String text(JsonNode n, String field) {
        String v = n.path(field).asText(null);
        return (v == null || v.isEmpty()) ? null : v;
    }
}
