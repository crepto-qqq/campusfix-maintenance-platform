package com.campusfix.user;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.campusfix.auth.EmailVerificationService;
import com.campusfix.auth.EmailVerificationService.VerificationResult;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final SystemRoleRepository systemRoleRepository;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final boolean emailVerificationRequired;

    public UserService(
            UserRepository userRepository,
            SystemRoleRepository systemRoleRepository,
            UserRoleAssignmentRepository roleAssignmentRepository,
            PasswordEncoder passwordEncoder,
            EmailVerificationService emailVerificationService,
            @Value("${campusfix.registration.email-verification.required:true}") boolean emailVerificationRequired) {
        this.userRepository = userRepository;
        this.systemRoleRepository = systemRoleRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailVerificationService = emailVerificationService;
        this.emailVerificationRequired = emailVerificationRequired;
    }

    @Transactional
    public User register(
            String name,
            String email,
            String password,
            IdentityType identityType,
            String verificationCode) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }

        if (emailVerificationRequired) {
            VerificationResult verificationResult = emailVerificationService.verifyCode(
                    normalizedEmail,
                    verificationCode);
            rejectInvalidVerificationCode(verificationResult);
        }

        User user = new User(
                name.trim(),
                normalizedEmail,
                passwordEncoder.encode(password),
                identityType);
        user.assignRole(requireSystemRole(Role.GENERAL_USER), null);
        User savedUser = userRepository.save(user);
        if (emailVerificationRequired) {
            emailVerificationService.consume(normalizedEmail);
        }
        return savedUser;
    }

    @Transactional
    public User login(String email, String password) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Invalid email or password"));

        if (user.getAccountStatus() != AccountStatus.ACTIVE
                || !passwordMatches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid email or password");
        }

        // Defensive upgrade for a partially migrated development database.
        if (!isBcryptHash(user.getPasswordHash())) {
            user.setPasswordHash(passwordEncoder.encode(password));
            userRepository.save(user);
        }

        return user;
    }

    @Transactional(readOnly = true)
    public User getProfile(Long userId) {
        return findUserById(userId);
    }

    @Transactional
    public User updateProfile(
            Long userId,
            String name,
            String contactInformation,
            String profileDetails) {
        User user = findUserById(userId);
        user.setName(name.trim());
        user.setContactInformation(trimOrNull(contactInformation));
        user.setProfileDetails(trimOrNull(profileDetails));
        return userRepository.save(user);
    }

    @Transactional
    public void updateAvatar(Long userId, byte[] avatarData, String avatarContentType) {
        User user = findUserById(userId);
        user.setAvatar(avatarData, avatarContentType);
        userRepository.save(user);
    }

    @Transactional
    public User updateRole(Long userId, Role role, User administrator) {
        lockAccessUpdates();
        User user = findUserById(userId);
        return updateAccess(userId, user.getIdentityType(), role, administrator);
    }

    @Transactional
    public User updateAccess(
            Long userId,
            IdentityType identityType,
            Role role,
            User administrator) {
        return updateAccess(userId, identityType, Set.of(role), administrator);
    }

    @Transactional
    public User updateAccess(Long userId, IdentityType identityType, Set<Role> roles, User administrator) {
        if (identityType == null || roles == null || roles.isEmpty() || roles.stream().anyMatch(Objects::isNull)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identity and at least one valid role are required");
        }
        // Serialise access updates so two administrators cannot concurrently revoke the last admins.
        lockAccessUpdates();
        User actor = findUserById(administrator.getId());
        if (actor.getAccountStatus() != AccountStatus.ACTIVE || !actor.hasRole(Role.ADMINISTRATOR)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access is required");
        }
        User user = findUserById(userId);
        if (user.hasRole(Role.ADMINISTRATOR)
                && !roles.contains(Role.ADMINISTRATOR)
                && roleAssignmentRepository.countBySystemRoleCodeAndActiveTrue(Role.ADMINISTRATOR) <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The last administrator cannot be demoted");
        }

        if (user.getIdentityType() != identityType) {
            user.changeIdentityType(identityType, administrator);
        }
        user.revokeRolesExcept(roles);
        for (Role selectedRole : roles) {
            user.assignRole(requireSystemRole(selectedRole), actor);
        }
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public List<User> getAllUsers() {
        return userRepository.findAllWithAccessOrderByNameAndEmail();
    }

    private void lockAccessUpdates() {
        systemRoleRepository.lockByCode(Role.ADMINISTRATOR)
                .orElseThrow(() -> new IllegalStateException("Administrator role is not configured"));
    }

    private User findUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "User not found"));
    }

    private SystemRole requireSystemRole(Role role) {
        return systemRoleRepository.findByCode(role)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "System role is not configured: " + role));
    }

    private boolean passwordMatches(String password, String storedPassword) {
        if (storedPassword == null) {
            return false;
        }
        return isBcryptHash(storedPassword)
                ? passwordEncoder.matches(password, storedPassword)
                : storedPassword.equals(password);
    }

    private boolean isBcryptHash(String value) {
        return value != null && value.matches("^\\$2[aby]\\$.*");
    }

    private String trimOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private void rejectInvalidVerificationCode(VerificationResult result) {
        switch (result) {
            case VALID -> {
                return;
            }
            case INVALID_FORMAT -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A valid 6-digit verification code is required");
            case NOT_REQUESTED -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Request a verification code before registering");
            case EXPIRED -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Verification code has expired. Request a new code");
            case TOO_MANY_ATTEMPTS -> throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many incorrect attempts. Request a new code");
            case INCORRECT -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Verification code is incorrect");
        }
    }
}
