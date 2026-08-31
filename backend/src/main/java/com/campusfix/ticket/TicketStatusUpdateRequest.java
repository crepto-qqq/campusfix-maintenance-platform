package com.campusfix.ticket;

import jakarta.validation.constraints.NotNull;

public record TicketStatusUpdateRequest(
        @NotNull(message = "Status is required")
        TicketStatus status) {
}
