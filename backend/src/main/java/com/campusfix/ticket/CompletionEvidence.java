package com.campusfix.ticket;

import java.time.LocalDateTime;

import com.campusfix.user.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "completion_evidence")
public class CompletionEvidence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false)
    private Ticket ticket;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;

    @Column(name = "repair_notes", nullable = false, length = 2000)
    private String repairNotes;

    @Lob
    @Column(name = "image_data", nullable = false)
    private byte[] imageData;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private LocalDateTime uploadedAt = LocalDateTime.now();

    protected CompletionEvidence() {
    }

    public CompletionEvidence(Ticket ticket, User uploadedBy, String repairNotes, byte[] imageData, String contentType) {
        this.ticket = ticket;
        this.uploadedBy = uploadedBy;
        this.repairNotes = repairNotes;
        this.imageData = imageData;
        this.contentType = contentType;
    }

    public Long getId() { return id; }
    public Ticket getTicket() { return ticket; }
    public User getUploadedBy() { return uploadedBy; }
    public String getRepairNotes() { return repairNotes; }
    public byte[] getImageData() { return imageData; }
    public String getContentType() { return contentType; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
}
