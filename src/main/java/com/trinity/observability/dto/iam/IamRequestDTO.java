package com.trinity.observability.dto.iam;

/**
 * Request bodies for the IAM API.
 *
 * <p>Only the one write this service exposes — see the note on
 * {@link com.trinity.observability.service.IamService} for why creation and token
 * issuance are deliberately absent.</p>
 */
public final class IamRequestDTO {

    private IamRequestDTO() {}

    /**
     * Changes to apply to an existing organization member.
     *
     * <p>A null field means "leave this as it is", which is why every field is a
     * boxed type: an empty first name is a legitimate value and must be
     * distinguishable from "not supplied".</p>
     */
    public static class UpdateUser {
        private String firstName;
        private String lastName;
        /** "admin" | "editor" | "viewer" | "user" | "root". */
        private String role;

        /**
         * Whether to also set a new password.
         *
         * <p>Separate from {@code newPassword} being non-null so that the intent
         * is explicit: a form that happens to leave a password box populated must
         * not silently reset someone's credential.</p>
         */
        private Boolean changePassword;
        private String newPassword;
        private String oldPassword;

        public String getFirstName()               { return firstName; }
        public void   setFirstName(String v)       { this.firstName = v; }

        public String getLastName()                { return lastName; }
        public void   setLastName(String v)        { this.lastName = v; }

        public String getRole()                    { return role; }
        public void   setRole(String v)            { this.role = v; }

        public Boolean getChangePassword()         { return changePassword; }
        public void    setChangePassword(Boolean v){ this.changePassword = v; }

        public String getNewPassword()             { return newPassword; }
        public void   setNewPassword(String v)     { this.newPassword = v; }

        public String getOldPassword()             { return oldPassword; }
        public void   setOldPassword(String v)     { this.oldPassword = v; }
    }
}
