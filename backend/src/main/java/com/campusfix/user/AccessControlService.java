package com.campusfix.user;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccessControlService {

    private final UserService userService;

    public AccessControlService(UserService userService) {
        this.userService = userService;
    }

    public User requireAuthenticatedUser(HttpSession session) {
        Object userId = session.getAttribute("userId");

        if (!(userId instanceof Long id)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login is required");
        }

        User user = userService.getProfile(id);
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account is inactive");
        }
        return user;
    }

    public User requireRole(HttpSession session, Role... allowedRoles) {
        User user = requireAuthenticatedUser(session);
        Role activeRole = requireActiveRole(user);
        if (Arrays.stream(allowedRoles).noneMatch(role -> role == activeRole)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission for this action");
        }

        return user;
    }

    public Set<Role> supportedRoles(User user) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        user.getRoles().stream().filter(Role::isSelectable).forEach(roles::add);
        return roles;
    }

    /** The browser tab supplies its workspace role on each request. */
    public Role resolveActiveRole(User user) {
        Set<Role> roles = supportedRoles(user);
        if (roles.size() == 1) {
            // An administrator may have revoked the old role while the tab was open.
            return roles.iterator().next();
        }
        Role requested = parseRole(requestedRole());
        return roles.contains(requested) ? requested : null;
    }

    public Role requireActiveRole(User user) {
        Set<Role> roles = supportedRoles(user);
        String supplied = requestedRole();
        if (supplied != null) {
            Role requested = parseRole(supplied);
            if (roles.contains(requested)) {
                return requested;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The selected role is no longer available");
        }
        if (roles.size() == 1) {
            return roles.iterator().next();
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Select an active role for this workspace");
    }

    public Role selectRole(User user, Role role) {
        if (role == null || !role.isSelectable() || !user.hasRole(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This role is not available for your account");
        }
        return role;
    }

    private String requestedRole() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        String header = request.getHeader("X-Active-Role");
        String value = header != null
                ? header
                : request.getParameter("activeRole");
        return value == null ? null : value.trim();
    }

    private Role parseRole(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Role.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
