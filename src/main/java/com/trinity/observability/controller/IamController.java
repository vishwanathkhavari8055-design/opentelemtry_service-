package com.trinity.observability.controller;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.trinity.observability.dto.iam.IamRequestDTO;
import com.trinity.observability.dto.iam.IamResponseDTO;
import com.trinity.observability.service.IamService;

import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Identity and access views for the UI's IAM screen.
 *
 * <p>Three reads plus one write ({@code PUT /users/{email}}). Creating users and
 * minting tokens deliberately stays in OpenObserve's own UI — see the note on
 * {@link IamService}, which also sets out the attribution caveat that applies to
 * the write.</p>
 *
 * <p>{@code /users} and {@code /service-accounts} are scoped to the request's
 * organization, which is taken from {@code ?org=} (or the {@code X-Org-Id}
 * header) by OrgContextFilter and defaults to the configured org.
 * {@code /organizations} is global — it is the list you switch between.</p>
 */
@RestController
@RequestMapping("/api/iam")
@Tag(name = "IAM API", description = "Organizations, users and service accounts")
public class IamController {

    private static final Logger log = LogManager.getLogger(IamController.class);

    private final IamService iamService;

    public IamController(IamService iamService) {
        this.iamService = iamService;
    }

    @GetMapping("/organizations")
    public IamResponseDTO.Page<IamResponseDTO.Organization> listOrganizations() {
        long t0 = System.nanoTime();
        log.info("GET /api/iam/organizations received");
        IamResponseDTO.Page<IamResponseDTO.Organization> result = iamService.listOrganizations();
        log.info("GET /api/iam/organizations returned {} org(s) (supported={}) in {} ms",
                result.getItems().size(), result.isSupported(), (System.nanoTime() - t0) / 1_000_000L);
        return result;
    }

    @GetMapping("/users")
    public IamResponseDTO.Page<IamResponseDTO.User> listUsers() {
        long t0 = System.nanoTime();
        log.info("GET /api/iam/users received");
        IamResponseDTO.Page<IamResponseDTO.User> result = iamService.listUsers();
        log.info("GET /api/iam/users returned {} user(s) for org={} (supported={}) in {} ms",
                result.getItems().size(), result.getOrg(), result.isSupported(),
                (System.nanoTime() - t0) / 1_000_000L);
        return result;
    }

    @GetMapping("/service-accounts")
    public IamResponseDTO.Page<IamResponseDTO.ServiceAccount> listServiceAccounts() {
        long t0 = System.nanoTime();
        log.info("GET /api/iam/service-accounts received");
        IamResponseDTO.Page<IamResponseDTO.ServiceAccount> result = iamService.listServiceAccounts();
        log.info("GET /api/iam/service-accounts returned {} account(s) for org={} (supported={}) in {} ms",
                result.getItems().size(), result.getOrg(), result.isSupported(),
                (System.nanoTime() - t0) / 1_000_000L);
        return result;
    }

    /**
     * Update an existing member of the request's organization.
     *
     * <p>The only write on this controller. Attribution caveat applies — see the
     * note on {@link IamService}.</p>
     */
    @PutMapping("/users/{email}")
    @SuppressWarnings("java:S2068")
    public IamResponseDTO.WriteResult updateUser(
            @PathVariable("email") String email,
            @RequestBody IamRequestDTO.UpdateUser body) {
        long t0 = System.nanoTime();
        // Logs the target and the shape of the change, never the credential.
        log.info("PUT /api/iam/users/{} received (role={}, pwdModified={})",
                email, body.getRole(), Boolean.TRUE.equals(body.getChangePassword()));
        IamResponseDTO.WriteResult result = iamService.updateUser(email, body);
        log.info("PUT /api/iam/users/{} -> ok={} in {} ms",
                email, result.isOk(), (System.nanoTime() - t0) / 1_000_000L);
        return result;
    }
}
