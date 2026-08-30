package com.campusfix.ticket;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TicketPriorityUpdateRequest(
        @NotNull(message = "Operational priority is required")
        TicketPriority priority,

        @Size(max = 1000, message = "Priority reason must not exceed 1000 characters")
        String reason) {
}
