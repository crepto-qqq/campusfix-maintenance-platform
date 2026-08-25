package com.campusfix.config;

import com.campusfix.user.Role;
import com.campusfix.user.IdentityType;
import com.campusfix.user.User;
import com.campusfix.user.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final String ADMIN_NAME = "Administrator";
    private static final String ADMIN_EMAIL = "admin@campusfix.edu";
    private static final String ADMIN_PASSWORD = "admin12345";

    private final UserRepository userRepository;

    public DataInitializer(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        for (User user : userRepository.findAll()) {
            if (user.getRole() == null) {
                user.setRole(Role.GENERAL_USER);
                userRepository.save(user);
            }

            if (user.getIdentityType() == null) {
                user.setIdentityType(IdentityType.STUDENT);
                userRepository.save(user);
            }
        }

        User admin = userRepository.findByEmailIgnoreCase(ADMIN_EMAIL)
                .orElseGet(() -> new User(
                        ADMIN_NAME,
                        ADMIN_EMAIL,
                        ADMIN_PASSWORD,
                        IdentityType.CAMPUS_STAFF));
        admin.setIdentityType(IdentityType.CAMPUS_STAFF);
        admin.setRole(Role.ADMINISTRATOR);
        userRepository.save(admin);
    }
}
