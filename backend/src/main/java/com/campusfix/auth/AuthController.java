package com.campusfix.auth;

import java.util.Set;

import com.campusfix.user.Role;
import com.campusfix.user.AccessControlService;
import com.campusfix.user.IdentityType;
import com.campusfix.user.User;
import com.campusfix.user.UserService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(
        origins = {"http://localhost:5500", "http://127.0.0.1:5500"},
        allowCredentials = "true"
)
public class AuthController {

    private final UserService userService;
    private final EmailVerificationService emailVerificationService;
    private final AccessControlService accessControlService;

    public AuthController(
            UserService userService,
            EmailVerificationService emailVerificationService,
            AccessControlService accessControlService) {
        this.userService = userService;
        this.emailVerificationService = emailVerificationService;
        this.accessControlService = accessControlService;
    }

    @PostMapping("/verification-code")
    public EmailVerificationService.VerificationCodeDelivery sendVerificationCode(
            @Valid @RequestBody VerificationCodeRequest request) {
        return emailVerificationService.requestCode(request.email());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegistrationResponse register(@Valid @RequestBody RegistrationRequest request) {
        User user = userService.register(
                request.name(),
                request.email(),
                request.password(),
                IdentityType.STUDENT,
                request.verificationCode());
        return new RegistrationResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getIdentityType(),
                user.getRole(),
                user.getRoles());
    }

    @PostMapping("/login")
    public LoginResponse login(
            @Valid @RequestBody LoginRequest request,
            HttpSession session) {
        User user = userService.login(request.email(), request.password());
        session.setAttribute("userId", user.getId());
        Set<Role> roles = accessControlService.supportedRoles(user);
        Role activeRole = roles.size() == 1 ? roles.iterator().next() : null;
        return new LoginResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getIdentityType(),
                user.getRole(),
                roles,
                activeRole);
    }

    @PostMapping("/active-role")
    public ActiveRoleResponse selectActiveRole(
            @Valid @RequestBody ActiveRoleRequest request,
            HttpSession session) {
        User user = accessControlService.requireAuthenticatedUser(session);
        Role activeRole = accessControlService.selectRole(user, request.role());
        return new ActiveRoleResponse(activeRole, accessControlService.supportedRoles(user));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpSession session) {
        session.invalidate();
    }

    public record RegistrationResponse(
            Long id,
            String name,
            String email,
            IdentityType identityType,
            Role role,
            Set<Role> roles) {
    }

    public record LoginResponse(
            Long id,
            String name,
            String email,
            IdentityType identityType,
            Role role,
            Set<Role> roles,
            Role activeRole) {
    }

    public record ActiveRoleRequest(@NotNull Role role) {
    }

    public record ActiveRoleResponse(Role activeRole, Set<Role> roles) {
    }
}
