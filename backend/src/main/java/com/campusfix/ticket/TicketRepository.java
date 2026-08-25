package com.campusfix.ticket;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    @EntityGraph(attributePaths = {"submittedBy", "images"})
    List<Ticket> findBySubmittedByIdOrderByCreatedAtDesc(Long submittedById);

    @EntityGraph(attributePaths = {"submittedBy", "images"})
    List<Ticket> findAllByOrderByCreatedAtDesc();
}
