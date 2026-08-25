package com.campusfix.config;

import com.campusfix.user.Role;
import com.campusfix.user.IdentityType;
import com.campusfix.user.User;
import com.campusfix.user.UserRepository;
import com.campusfix.ticket.Ticket;
import com.campusfix.ticket.TicketEventType;
import com.campusfix.ticket.TicketHistory;
import com.campusfix.ticket.TicketHistoryRepository;
import com.campusfix.ticket.TicketRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final String ADMIN_NAME = "Administrator";
    private static final String ADMIN_EMAIL = "admin@campusfix.edu";
    private static final String ADMIN_PASSWORD = "admin12345";

    private final UserRepository userRepository;
    private final TicketRepository ticketRepository;
    private final TicketHistoryRepository ticketHistoryRepository;
    private final JdbcTemplate jdbcTemplate;

    public DataInitializer(
            UserRepository userRepository,
            TicketRepository ticketRepository,
            TicketHistoryRepository ticketHistoryRepository,
            JdbcTemplate jdbcTemplate) {
        this.userRepository = userRepository;
        this.ticketRepository = ticketRepository;
        this.ticketHistoryRepository = ticketHistoryRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void run(String... args) {
        jdbcTemplate.execute("""
                ALTER TABLE tickets ALTER COLUMN status
                ENUM(
                    'SUBMITTED',
                    'ASSIGNED',
                    'IN_PROGRESS',
                    'COMPLETED',
                    'CLOSED',
                    'REOPENED',
                    'CANCELLED',
                    'PENDING'
                ) NOT NULL
                """);
        jdbcTemplate.update(
                "UPDATE tickets SET status = 'SUBMITTED' WHERE CAST(status AS VARCHAR) = 'PENDING'");

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

        for (Ticket ticket : ticketRepository.findAll()) {
            if (!ticketHistoryRepository.existsByTicketId(ticket.getId())) {
                ticketHistoryRepository.save(new TicketHistory(
                        ticket,
                        TicketEventType.LEGACY_IMPORTED,
                        ticket.getSubmittedBy(),
                        null,
                        "status=" + ticket.getStatus() + " | imported=true",
                        "Existing ticket imported when F15 audit history was enabled"));
            }
        }
    }
}
