package com.campusfix.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.campusfix.ticket.Ticket;
import com.campusfix.ticket.TicketCategory;
import com.campusfix.ticket.TicketRequest;
import com.campusfix.ticket.TicketService;
import com.campusfix.ticket.UrgencyLevel;
import com.campusfix.user.User;
import com.campusfix.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:legacy-event-columns;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "campusfix.bootstrap-admin.password=integration-test-admin-password",
        "campusfix.registration.email-verification.required=false"
})
class LegacyEventColumnMigrationTest {
    @Autowired private DataInitializer initializer;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TicketService tickets;
    @Autowired private UserRepository users;

    @Test
    void existingH2EnumsConvertToOpenVarcharsWithoutLosingEvents() {
        User administrator = users.findByEmailIgnoreCase("admin@campusfix.edu").orElseThrow();
        Ticket ticket = tickets.createTicket(new TicketRequest(
                "Legacy event migration", "Keep old audit records",
                TicketCategory.OTHER, "Lab", null, null, null, null,
                UrgencyLevel.MEDIUM), administrator);

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_SCHEMA='PUBLIC' AND CONSTRAINT_TYPE='CHECK' "
                        + "AND TABLE_NAME IN ('TICKET_HISTORY', 'IN_APP_NOTIFICATIONS')",
                Integer.class));

        jdbc.execute("ALTER TABLE ticket_history ALTER COLUMN event_type "
                + "ENUM('TICKET_CREATED', 'LEGACY_IMPORTED') NOT NULL");
        jdbc.execute("ALTER TABLE in_app_notifications ALTER COLUMN type "
                + "ENUM('STATUS_CHANGED') NOT NULL");
        jdbc.update("INSERT INTO in_app_notifications "
                + "(recipient_id, actor_id, ticket_id, type, message, deduplication_key, created_at) "
                + "VALUES (?, ?, ?, 'STATUS_CHANGED', 'Existing notification', 'legacy-event-migration', CURRENT_TIMESTAMP)",
                administrator.getId(), administrator.getId(), ticket.getId());

        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO ticket_history (ticket_id, actor_id, event_type, occurred_at) "
                        + "VALUES (?, ?, 'COMPLETION_EVIDENCE_ADDED', CURRENT_TIMESTAMP)",
                ticket.getId(), administrator.getId()));
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO in_app_notifications "
                        + "(recipient_id, actor_id, ticket_id, type, message, deduplication_key, created_at) "
                        + "VALUES (?, ?, ?, 'REVIEW_BANNED', 'New notification', 'new-event-migration', CURRENT_TIMESTAMP)",
                administrator.getId(), administrator.getId(), ticket.getId()));

        initializer.migrateEventTypeColumns();
        initializer.migrateEventTypeColumns();

        jdbc.update("INSERT INTO ticket_history (ticket_id, actor_id, event_type, occurred_at) "
                        + "VALUES (?, ?, 'COMPLETION_EVIDENCE_ADDED', CURRENT_TIMESTAMP)",
                ticket.getId(), administrator.getId());
        jdbc.update("INSERT INTO in_app_notifications "
                        + "(recipient_id, actor_id, ticket_id, type, message, deduplication_key, created_at) "
                        + "VALUES (?, ?, ?, 'REVIEW_BANNED', 'New notification', 'new-event-migration', CURRENT_TIMESTAMP)",
                administrator.getId(), administrator.getId(), ticket.getId());

        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_history WHERE ticket_id = ? AND event_type = 'TICKET_CREATED'",
                Integer.class, ticket.getId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_history WHERE ticket_id = ? AND event_type = 'COMPLETION_EVIDENCE_ADDED'",
                Integer.class, ticket.getId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM in_app_notifications WHERE type = 'STATUS_CHANGED'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM in_app_notifications WHERE type = 'REVIEW_BANNED'",
                Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA='PUBLIC' "
                        + "AND DATA_TYPE='CHARACTER VARYING' AND "
                        + "((TABLE_NAME='TICKET_HISTORY' AND COLUMN_NAME='EVENT_TYPE') "
                        + "OR (TABLE_NAME='IN_APP_NOTIFICATIONS' AND COLUMN_NAME='TYPE'))",
                Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_SCHEMA='PUBLIC' AND CONSTRAINT_TYPE='CHECK' "
                        + "AND TABLE_NAME IN ('TICKET_HISTORY', 'IN_APP_NOTIFICATIONS')",
                Integer.class));
    }
}
