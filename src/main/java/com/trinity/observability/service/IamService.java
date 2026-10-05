package com.trinity.observability.service;

import com.trinity.observability.dto.iam.IamRequestDTO;
import com.trinity.observability.dto.iam.IamResponseDTO;

/**
 * Views over OpenObserve's identity endpoints, backing the UI's IAM screen.
 *
 * <p>Reads, plus ONE write: {@link #updateUser}. Creating users, minting
 * service-account tokens and adding organizations remain absent, and the reason
 * is worth restating because it also qualifies the write that does exist: this
 * service authenticates to OpenObserve with a single shared service account, so
 * every write it performs is attributed to that account rather than to the person
 * who asked for it. OpenObserve's own audit trail will therefore show the service
 * account as the editor of a user record changed here.</p>
 *
 * <p>Editing an existing member's name and role was accepted on that basis
 * because it is reversible and visible in the same screen. Creation and token
 * issuance were not: an account or credential that appears with no real author is
 * a much worse thing to have to explain afterwards. Both belong in OpenObserve's
 * own UI until this deployment has real per-user identity — see the note in
 * AuthContext/vite.config.js about the current auth being mocked.</p>
 *
 * <p>OpenObserve-only. On the Elasticsearch path every read answers
 * {@code supported=false} with an empty list rather than throwing, and the write
 * throws rather than pretending to have succeeded.</p>
 */
public interface IamService {

    /** Every organization on the instance — the list the org switcher offers. */
    IamResponseDTO.Page<IamResponseDTO.Organization> listOrganizations();

    /** Members of the request's organization. */
    IamResponseDTO.Page<IamResponseDTO.User> listUsers();

    /** Service accounts belonging to the request's organization. */
    IamResponseDTO.Page<IamResponseDTO.ServiceAccount> listServiceAccounts();

    /**
     * Update a member of the request's organization.
     *
     * <p>Only the fields present in {@code update} are sent on; a null leaves
     * that attribute as it is. The email is the key and cannot be changed —
     * OpenObserve identifies users by it.</p>
     *
     * @param email  the member to update; must already exist in this org
     * @param update the changes to apply
     * @throws IllegalStateException on the Elasticsearch path
     */
    IamResponseDTO.WriteResult updateUser(String email, IamRequestDTO.UpdateUser update);
}
