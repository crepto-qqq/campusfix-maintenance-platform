package com.campusfix.user;

import java.util.Set;

import java.io.IOException;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(
        origins = {"http://localhost:5500", "http://127.0.0.1:5500"},
        allowCredentials = "true"
)
public class UserController {

    private static final long MAX_AVATAR_SIZE_BYTES = 15 * 1024 * 1024;

    private final UserService userService;
    private final AccessControlService accessControlService;

    public UserController(UserService userService, AccessControlService accessControlService) {
        this.userService = userService;
        this.accessControlService = accessControlService;
    }

    @GetMapping("/me")
    public ProfileResponse getMyProfile(HttpSession session) {
        User user = accessControlService.requireAuthenticatedUser(session);
        return createProfileResponse(user);
    }

    @PutMapping("/me")
    public ProfileResponse updateMyProfile(
            @Valid @RequestBody ProfileUpdateRequest request,
            HttpSession session) {
        User user = userService.updateProfile(
                accessControlService.requireAuthenticatedUser(session).getId(),
                request.name(),
                request.contactInformation(),
                request.profileDetails());
        return createProfileResponse(user);
    }

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uploadMyAvatar(
            @RequestParam("avatar") MultipartFile avatar,
            HttpSession session) {
        validateAvatar(avatar);

        try {
            userService.updateAvatar(
                    accessControlService.requireAuthenticatedUser(session).getId(),
                    avatar.getBytes(),
                    avatar.getContentType());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read image");
        }
    }

    @GetMapping("/me/avatar")
    public ResponseEntity<byte[]> getMyAvatar(HttpSession session) {
        User user = accessControlService.requireAuthenticatedUser(session);

        if (user.getAvatarData() == null || user.getAvatarContentType() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Avatar not found");
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(user.getAvatarContentType()))
                .body(user.getAvatarData());
    }

    private void validateAvatar(MultipartFile avatar) {
        if (avatar.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image is required");
        }

        if (avatar.getSize() > MAX_AVATAR_SIZE_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Image must not exceed 15 MB");
        }

        String contentType = avatar.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File must be an image");
        }
    }

    private ProfileResponse createProfileResponse(User user) {
        return new ProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getIdentityType(),
                user.getRole(),
                user.getRoles(),
                user.getContactInformation(),
                user.getProfileDetails());
    }

    public record ProfileResponse(
            Long id,
            String email,
            String name,
            IdentityType identityType,
            Role role,
            Set<Role> roles,
            String contactInformation,
            String profileDetails) {
    }
}
