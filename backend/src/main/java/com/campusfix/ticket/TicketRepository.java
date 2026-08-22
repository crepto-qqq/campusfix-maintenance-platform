package com.campusfix.ticket;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findBySubmittedByIdOrderByCreatedAtDesc(Long submittedById);
}