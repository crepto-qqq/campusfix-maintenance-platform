package com.campusfix.user;

import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final SystemRoleRepository systemRoleRepository;
    private final UserRoleAssignmentRepository roleAssignmentRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserRepository userRepository,
            SystemRoleRepository systemRoleRepository,
            UserRoleAssignmentRepository roleAssignmentRepository,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.systemRoleRepository = systemRoleRepository;
        this.roleAssignmentRepository = roleAssignmentRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public User register(String name, String email, String password, IdentityType identityType) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }

        User user = new User(
                name.trim(),
                normalizedEmail,
                passwordEncoder.encode(password),
                identityType);
        user.assignRole(requireSystemRole(Role.GENERAL_USER), null);
        return userRepository.save(user);
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
        User user = findUserById(userId);
        return updateAccess(userId, user.getIdentityType(), role, administrator);
    }

    @Transactional
    public User updateAccess(
            Long userId,
            IdentityType identityType,
            Role role,
            User administrator) {
        User user = findUserById(userId);

        if (user.getRole() == Role.ADMINISTRATOR
                && role != Role.ADMINISTRATOR
                && roleAssignmentRepository.countBySystemRoleCodeAndActiveTrue(Role.ADMINISTRATOR) <= 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The last administrator cannot be demoted");
        }

        if (user.getIdentityType() != identityType) {
            user.changeIdentityType(identityType, administrator);
        }
        user.assignRole(requireSystemRole(role), administrator);
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public List<User> getAllUsers() {
        return userRepository.findAllWithAccessOrderByNameAndEmail();
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
}
