package com.campusfix.user;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_role_assignments")
public class UserRoleAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private SystemRole systemRole;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by")
    private User assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private LocalDateTime assignedAt = LocalDateTime.now();

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected UserRoleAssignment() {
    }

    public UserRoleAssignment(User user, SystemRole systemRole, User assignedBy) {
        this.user = user;
        this.systemRole = systemRole;
        this.assignedBy = assignedBy;
    }

    public Long getId() {
        return id;
    }

    public SystemRole getSystemRole() {
        return systemRole;
    }

    public LocalDateTime getAssignedAt() {
        return assignedAt;
    }

    public boolean isActive() {
        return active;
    }

    public void revoke() {
        this.active = false;
        this.revokedAt = LocalDateTime.now();
    }
}
