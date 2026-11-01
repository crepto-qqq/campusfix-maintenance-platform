package com.campusfix.ticket;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TicketAssignmentRequest(
        @NotNull(message = "Technician is required")
        @Positive(message = "Technician ID must be positive")
        Long technicianId) {
}
