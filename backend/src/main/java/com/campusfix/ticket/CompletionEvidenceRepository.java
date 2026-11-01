package com.campusfix.ticket;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CompletionEvidenceRepository extends JpaRepository<CompletionEvidence, Long> {
    List<CompletionEvidence> findByTicketIdOrderByUploadedAtDesc(Long ticketId);
}
