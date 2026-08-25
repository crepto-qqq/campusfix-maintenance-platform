package com.campusfix.user;

import jakarta.validation.constraints.NotNull;

public record AdminUserUpdateRequest(
        @NotNull(message = "Identity type is required")
        IdentityType identityType,

        @NotNull(message = "Role is required")
        Role role) {
}
