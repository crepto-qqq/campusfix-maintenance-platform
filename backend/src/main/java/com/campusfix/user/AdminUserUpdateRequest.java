package com.campusfix.user;

import java.util.Set;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record AdminUserUpdateRequest(
        @NotNull(message = "Identity type is required") IdentityType identityType,
        Role role,
        Set<@NotNull Role> roles) {

    @AssertTrue(message = "Provide either one role or a non-empty roles list")
    public boolean isRoleSelectionValid() {
        return (role != null && roles == null)
                || (role == null && roles != null && !roles.isEmpty());
    }

    public Set<Role> selectedRoles() {
        return roles == null ? Set.of(role) : roles;
    }
}
