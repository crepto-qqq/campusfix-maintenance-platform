package com.campusfix.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record TicketReassignmentRequest(
        @NotNull(message = "Technician is required")
        @Positive(message = "Technician ID must be positive")
        Long technicianId,

        @NotBlank(message = "Reassignment reason is required")
        @Size(max = 1000, message = "Reassignment reason must not exceed 1000 characters")
        String reason) {
}
