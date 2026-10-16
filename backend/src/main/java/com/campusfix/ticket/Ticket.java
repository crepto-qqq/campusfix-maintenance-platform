package com.campusfix.ticket;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.campusfix.user.User;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "tickets",
        indexes = @Index(
                name = "idx_tickets_operational_priority",
                columnList = "operational_priority"))
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String title;

    @Column(nullable = false, length = 4000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TicketCategory category;

    @Column(nullable = false, length = 255)
    private String location;

    @Column(name = "google_place_id", length = 255)
    private String googlePlaceId;

    private Double latitude;

    private Double longitude;

    @Column(name = "location_details", length = 500)
    private String locationDetails;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private UrgencyLevel urgency;

    @Enumerated(EnumType.STRING)
    @Column(name = "operational_priority", length = 30)
    private TicketPriority operationalPriority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TicketStatus status = TicketStatus.SUBMITTED;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submitted_by", nullable = false)
    private User submittedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<TicketImage> images = new ArrayList<>();

    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Set<TicketAssignment> assignments = new LinkedHashSet<>();

    protected Ticket() {
    }

    public Ticket(
            String title,
            String description,
            TicketCategory category,
            String location,
            UrgencyLevel urgency,
            User submittedBy) {
        this.title = title;
        this.description = description;
        this.category = category;
        this.location = location;
        this.urgency = urgency;
        this.submittedBy = submittedBy;
    }

    public void touch() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public TicketCategory getCategory() {
        return category;
    }

    public void setCategory(TicketCategory category) {
        this.category = category;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getGooglePlaceId() {
        return googlePlaceId;
    }

    public void setGooglePlaceId(String googlePlaceId) {
        this.googlePlaceId = googlePlaceId;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getLocationDetails() {
        return locationDetails;
    }

    public void setLocationDetails(String locationDetails) {
        this.locationDetails = locationDetails;
    }

    public UrgencyLevel getUrgency() {
        return urgency;
    }

    public void setUrgency(UrgencyLevel urgency) {
        this.urgency = urgency;
    }

    public TicketPriority getOperationalPriority() {
        return operationalPriority;
    }

    public void setOperationalPriority(TicketPriority operationalPriority) {
        this.operationalPriority = operationalPriority;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public void setStatus(TicketStatus status) {
        this.status = status;
    }

    public User getSubmittedBy() {
        return submittedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public List<TicketImage> getImages() {
        return images;
    }

    public void addImage(TicketImage image) {
        image.setTicket(this);
        this.images.add(image);
    }

    public Set<TicketAssignment> getAssignments() {
        return assignments;
    }

    public TicketAssignment getActiveAssignment() {
        return assignments.stream()
                .filter(TicketAssignment::isActive)
                .findFirst()
                .orElse(null);
    }

    public TicketAssignment assignTo(User technician, User administrator) {
        TicketAssignment assignment = new TicketAssignment(this, technician, administrator);
        assignments.add(assignment);
        return assignment;
    }
}
