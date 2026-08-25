package com.campusfix.user;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public User register(String name, String email, String password, IdentityType identityType) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email is already registered");
        }

        User user = new User(name.trim(), normalizedEmail, password, identityType);
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public User login(String email, String password) {
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        User user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Invalid email or password"));

        if (!user.getPassword().equals(password)) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid email or password");
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
    public User updateRole(Long userId, Role role) {
        User user = findUserById(userId);
        return updateAccess(userId, user.getIdentityType(), role);
    }

    @Transactional
    public User updateAccess(Long userId, IdentityType identityType, Role role) {
        User user = findUserById(userId);

        if (user.getRole() == Role.ADMINISTRATOR
                && role != Role.ADMINISTRATOR
                && userRepository.countByRole(Role.ADMINISTRATOR) <= 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "The last administrator cannot be demoted");
        }

        user.setIdentityType(identityType);
        user.setRole(role);
        return userRepository.save(user);
    }

    @Transactional(readOnly = true)
    public java.util.List<User> getAllUsers() {
        return userRepository.findAllByOrderByNameAscEmailAsc();
    }

    private User findUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "User not found"));
    }

    private String trimOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }
}
