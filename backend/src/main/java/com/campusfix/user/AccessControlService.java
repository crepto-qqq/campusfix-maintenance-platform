package com.campusfix.user;

import java.util.Arrays;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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

        if (Arrays.stream(allowedRoles).noneMatch(role -> user.hasRole(role))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission for this action");
        }

        return user;
    }
}
