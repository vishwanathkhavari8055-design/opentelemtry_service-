package com.trinity.observability.dto.iam;

import java.util.ArrayList;
import java.util.List;

/**
 * Responses for the IAM screen: organizations, org members, and service
 * accounts.
 *
 * <p>One file with three nested shapes rather than six top-level DTOs — they
 * are only ever used together by one screen, and each is a handful of fields
 * read straight from OpenObserve's own admin endpoints.</p>
 *
 * <p>Every list is org-scoped except {@link Organizations}, which is
 * necessarily global — it is the list you pick FROM.</p>
 */
public final class IamResponseDTO {

    private IamResponseDTO() {}

    /** One row of the Organizations table. */
    public static class Organization {
        private Integer id;
        /** The value that goes in an OpenObserve URL path, e.g. "_meta". */
        private String identifier;
        private String name;
        /** "custom" | "default", shown in the Type column. */
        private String type;
        private String ownerEmail;

        public Integer getId()                    { return id; }
        public void    setId(Integer v)           { this.id = v; }

        public String getIdentifier()             { return identifier; }
        public void   setIdentifier(String v)     { this.identifier = v; }

        public String getName()                   { return name; }
        public void   setName(String v)           { this.name = v; }

        public String getType()                   { return type; }
        public void   setType(String v)           { this.type = v; }

        public String getOwnerEmail()             { return ownerEmail; }
        public void   setOwnerEmail(String v)     { this.ownerEmail = v; }
    }

    /** One row of the Users table. */
    public static class User {
        private String email;
        private String firstName;
        private String lastName;
        private String role;
        private Boolean external;
        /** ISO-8601, converted from OpenObserve's epoch MICROSECONDS. */
        private String createdAt;

        public String getEmail()                  { return email; }
        public void   setEmail(String v)          { this.email = v; }

        public String getFirstName()              { return firstName; }
        public void   setFirstName(String v)      { this.firstName = v; }

        public String getLastName()               { return lastName; }
        public void   setLastName(String v)       { this.lastName = v; }

        public String getRole()                   { return role; }
        public void   setRole(String v)           { this.role = v; }

        public Boolean getExternal()              { return external; }
        public void    setExternal(Boolean v)     { this.external = v; }

        public String getCreatedAt()              { return createdAt; }
        public void   setCreatedAt(String v)      { this.createdAt = v; }
    }

    /** One row of the Service Accounts table. */
    public static class ServiceAccount {
        private String email;
        private String firstName;
        private String lastName;
        private String description;
        /**
         * Deliberately NOT the token itself.
         *
         * <p>OpenObserve's list endpoint does not return service-account
         * tokens, and this service will not invent a way to surface them: a
         * token is a bearer credential, and putting one in a list payload
         * means it lands in browser memory, logs and any proxy in between. The
         * UI shows a "reveal" affordance that points at OpenObserve, which is
         * where the credential can be re-issued under its own audit trail.</p>
         */
        private Boolean hasToken;

        public String getEmail()                  { return email; }
        public void   setEmail(String v)          { this.email = v; }

        public String getFirstName()              { return firstName; }
        public void   setFirstName(String v)      { this.firstName = v; }

        public String getLastName()               { return lastName; }
        public void   setLastName(String v)       { this.lastName = v; }

        public String getDescription()            { return description; }
        public void   setDescription(String v)    { this.description = v; }

        public Boolean getHasToken()              { return hasToken; }
        public void    setHasToken(Boolean v)     { this.hasToken = v; }
    }

    /**
     * Result of the one IAM write.
     *
     * <p>Carries OpenObserve's own message rather than inventing one, so a
     * rejection reason ("Root user cannot be updated", a password-policy failure)
     * reaches the person who tried.</p>
     */
    public static class WriteResult {
        private boolean ok;
        private String message;
        private String email;

        public boolean isOk()                     { return ok; }
        public void    setOk(boolean v)           { this.ok = v; }

        public String getMessage()                { return message; }
        public void   setMessage(String v)        { this.message = v; }

        public String getEmail()                  { return email; }
        public void   setEmail(String v)          { this.email = v; }
    }

    /** Envelope shared by all three lists. */
    public static class Page<T> {
        private List<T> items = new ArrayList<>();
        /** The org these items describe; null for the global org list. */
        private String org;
        private boolean supported;
        private String backend;

        public List<T> getItems()             { return items; }
        public void    setItems(List<T> v)    { this.items = v; }

        public String getOrg()                { return org; }
        public void   setOrg(String v)        { this.org = v; }

        public boolean isSupported()          { return supported; }
        public void    setSupported(boolean v){ this.supported = v; }

        public String getBackend()            { return backend; }
        public void   setBackend(String v)    { this.backend = v; }
    }
}
