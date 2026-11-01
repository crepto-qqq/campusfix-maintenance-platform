package com.campusfix.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProfileUpdateRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 100, message = "Name must not exceed 100 characters")
        String name,

        @Size(max = 255, message = "Contact information must not exceed 255 characters")
        String contactInformation,

        @Size(max = 1000, message = "Profile details must not exceed 1000 characters")
        String profileDetails) {
}
