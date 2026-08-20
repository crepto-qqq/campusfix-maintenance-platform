package com.campusfix.user;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/users")
@CrossOrigin(
        origins = {"http://localhost:5500", "http://127.0.0.1:5500"},
        allowCredentials = "true"
)
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ProfileResponse getMyProfile(HttpSession session) {
        User user = userService.getProfile(getLoggedInUserId(session));
        return createProfileResponse(user);
    }

    @PutMapping("/me")
    public ProfileResponse updateMyProfile(
            @Valid @RequestBody ProfileUpdateRequest request,
            HttpSession session) {
        User user = userService.updateProfile(
                getLoggedInUserId(session),
                request.name(),
                request.contactInformation(),
                request.profileDetails());
        return createProfileResponse(user);
    }

    private Long getLoggedInUserId(HttpSession session) {
        Object userId = session.getAttribute("userId");

        if (userId instanceof Long id) {
            return id;
        }

        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login is required");
    }

    private ProfileResponse createProfileResponse(User user) {
        return new ProfileResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getContactInformation(),
                user.getProfileDetails());
    }

    public record ProfileResponse(
            Long id,
            String email,
            String name,
            String contactInformation,
            String profileDetails) {
    }
}
