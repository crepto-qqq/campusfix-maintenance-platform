package com.campusfix.user;

import java.util.List;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/users")
@CrossOrigin(
        origins = {"http://localhost:5500", "http://127.0.0.1:5500"},
        allowCredentials = "true"
)
public class AdminUserController {

    private final UserService userService;
    private final AccessControlService accessControlService;

    public AdminUserController(
            UserService userService,
            AccessControlService accessControlService) {
        this.userService = userService;
        this.accessControlService = accessControlService;
    }

    @GetMapping
    public List<AdminUserResponse> getUsers(HttpSession session) {
        accessControlService.requireRole(session, Role.ADMINISTRATOR);
        return userService.getAllUsers().stream()
                .map(this::createResponse)
                .toList();
    }

    @PutMapping("/{userId}/role")
    public AdminUserResponse updateRole(
            @PathVariable("userId") Long userId,
            @Valid @RequestBody RoleUpdateRequest request,
            HttpSession session) {
        accessControlService.requireRole(session, Role.ADMINISTRATOR);
        return createResponse(userService.updateRole(userId, request.role()));
    }

    @PutMapping("/{userId}")
    public AdminUserResponse updateAccess(
            @PathVariable("userId") Long userId,
            @Valid @RequestBody AdminUserUpdateRequest request,
            HttpSession session) {
        accessControlService.requireRole(session, Role.ADMINISTRATOR);
        return createResponse(userService.updateAccess(
                userId,
                request.identityType(),
                request.role()));
    }

    private AdminUserResponse createResponse(User user) {
        return new AdminUserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getIdentityType(),
                user.getRole());
    }

    public record AdminUserResponse(
            Long id,
            String name,
            String email,
            IdentityType identityType,
            Role role) {
    }
}
