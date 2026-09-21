package com.campusfix.ticket;

public record TicketPrioritySummary(
        long unclassified,
        long low,
        long medium,
        long high,
        long critical) {
}
