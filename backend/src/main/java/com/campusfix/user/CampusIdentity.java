package com.campusfix.user;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "campus_identities")
public class CampusIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "identity_type", nullable = false, length = 30)
    private IdentityType identityType;

    @Column(name = "university_id", unique = true, length = 50)
    private String universityId;

    @Column(length = 150)
    private String department;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by")
    private User verifiedBy;

    protected CampusIdentity() {
    }

    public CampusIdentity(User user, IdentityType identityType) {
        this.user = user;
        this.identityType = identityType;
    }

    public IdentityType getIdentityType() {
        return identityType;
    }

    public void changeIdentityType(IdentityType identityType, User verifiedBy) {
        this.identityType = identityType;
        this.verifiedBy = verifiedBy;
        this.verifiedAt = LocalDateTime.now();
    }
}
