package com.campusfix.user;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/**
 * Authentication account. Personal, campus identity and authorisation data are
 * stored in separate relational tables and exposed through compatibility
 * getters so the existing API contract remains unchanged.
 */
@Entity
@Table(name = "app_users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    // Nullable in the mapping only so Hibernate can add the column to the
    // legacy table before DataInitializer hashes and migrates existing values.
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", length = 30)
    private AccountStatus accountStatus = AccountStatus.ACTIVE;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private UserProfile profile;

    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private CampusIdentity campusIdentity;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private Set<UserRoleAssignment> roleAssignments = new LinkedHashSet<>();

    protected User() {
    }

    public User(
            String name,
            String email,
            String passwordHash,
            IdentityType identityType) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.profile = new UserProfile(this, name);
        this.campusIdentity = new CampusIdentity(this, identityType);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
        touch();
    }

    public AccountStatus getAccountStatus() {
        return accountStatus;
    }

    public String getName() {
        return profile == null ? null : profile.getName();
    }

    public void setName(String name) {
        requireProfile().setName(name);
        touch();
    }

    public IdentityType getIdentityType() {
        return campusIdentity == null ? null : campusIdentity.getIdentityType();
    }

    public void changeIdentityType(IdentityType identityType, User verifiedBy) {
        requireCampusIdentity(identityType).changeIdentityType(identityType, verifiedBy);
        touch();
    }

    /** A deterministic display role for older clients, never an authorisation decision. */
    public Role getRole() {
        for (Role role : new Role[] {Role.ADMINISTRATOR, Role.MANAGER,
                Role.MAINTENANCE_STAFF, Role.GENERAL_USER}) {
            if (hasRole(role)) {
                return role;
            }
        }
        return null;
    }

    public Set<Role> getRoles() {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        roleAssignments.stream().filter(UserRoleAssignment::isActive)
                .map(assignment -> assignment.getSystemRole().getCode())
                .forEach(roles::add);
        return roles;
    }

    public boolean hasRole(Role role) {
        return roleAssignments.stream()
                .filter(UserRoleAssignment::isActive)
                .anyMatch(assignment ->
                        assignment.getSystemRole().getCode() == role);
    }

    public void assignRole(SystemRole systemRole, User assignedBy) {
        if (hasRole(systemRole.getCode())) {
            return;
        }

        roleAssignments.add(new UserRoleAssignment(this, systemRole, assignedBy));
        touch();
    }

    public void revokeRolesExcept(Set<Role> retainedRoles) {
        roleAssignments.stream().filter(UserRoleAssignment::isActive)
                .filter(assignment -> !retainedRoles.contains(assignment.getSystemRole().getCode()))
                .forEach(UserRoleAssignment::revoke);
        touch();
    }

    public String getContactInformation() {
        return profile == null ? null : profile.getContactInformation();
    }

    public void setContactInformation(String contactInformation) {
        requireProfile().setContactInformation(contactInformation);
        touch();
    }

    public String getProfileDetails() {
        return profile == null ? null : profile.getProfileDetails();
    }

    public void setProfileDetails(String profileDetails) {
        requireProfile().setProfileDetails(profileDetails);
        touch();
    }

    public byte[] getAvatarData() {
        return profile == null ? null : profile.getAvatarData();
    }

    public String getAvatarContentType() {
        return profile == null ? null : profile.getAvatarContentType();
    }

    public void setAvatar(byte[] avatarData, String avatarContentType) {
        requireProfile().setAvatar(avatarData, avatarContentType);
        touch();
    }

    private UserProfile requireProfile() {
        if (profile == null) {
            profile = new UserProfile(this, email);
        }
        return profile;
    }

    private CampusIdentity requireCampusIdentity(IdentityType defaultType) {
        if (campusIdentity == null) {
            campusIdentity = new CampusIdentity(this, defaultType);
        }
        return campusIdentity;
    }

    private void touch() {
        updatedAt = LocalDateTime.now();
    }
}
