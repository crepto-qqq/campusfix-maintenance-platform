package com.campusfix.ticket;

public enum TicketStatus {
    SUBMITTED,
    ASSIGNED,
    IN_PROGRESS,
    COMPLETED,
    CLOSED,
    REOPENED,
    CANCELLED,

    // Kept temporarily so Hibernate can load and migrate databases created
    // before F15. New and updated tickets must never use this value.
    @Deprecated
    PENDING
}
