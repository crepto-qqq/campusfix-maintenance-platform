package com.campusfix.config;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.campusfix.ticket.Ticket;
import com.campusfix.ticket.TicketEventType;
import com.campusfix.ticket.TicketHistory;
import com.campusfix.ticket.TicketHistoryRepository;
import com.campusfix.ticket.TicketRepository;
import com.campusfix.user.AccountStatus;
import com.campusfix.user.IdentityType;
import com.campusfix.user.Role;
import com.campusfix.user.SystemRole;
import com.campusfix.user.SystemRoleRepository;
import com.campusfix.user.User;
import com.campusfix.user.UserRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final String ADMIN_NAME = "Administrator";
    private static final String ADMIN_EMAIL = "admin@campusfix.edu";

    private final UserRepository userRepository;
    private final SystemRoleRepository systemRoleRepository;
    private final TicketRepository ticketRepository;
    private final TicketHistoryRepository ticketHistoryRepository;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapAdminPassword;

    public DataInitializer(
            UserRepository userRepository,
            SystemRoleRepository systemRoleRepository,
            TicketRepository ticketRepository,
            TicketHistoryRepository ticketHistoryRepository,
            JdbcTemplate jdbcTemplate,
            PasswordEncoder passwordEncoder,
            @Value("${campusfix.bootstrap-admin.password:}") String bootstrapAdminPassword) {
        this.userRepository = userRepository;
        this.systemRoleRepository = systemRoleRepository;
        this.ticketRepository = ticketRepository;
        this.ticketHistoryRepository = ticketHistoryRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapAdminPassword = bootstrapAdminPassword;
    }

    @Override
    @Transactional
    public void run(String... args) {
        seedSystemRoles();
        migrateLegacyUserTable();
        enforceAccountConstraints();
        ensureRelationalUserData();
        migrateManagerAssignments();
        ensureAdministrator();
        migrateTicketStatuses();
        backfillTicketHistory();
    }

    private void seedSystemRoles() {
        ensureSystemRole(
                Role.GENERAL_USER,
                "Requester",
                "Submits and tracks maintenance requests");
        ensureSystemRole(
                Role.MAINTENANCE_STAFF,
                "Maintenance Staff",
                "Works on assigned maintenance requests");
        ensureSystemRole(
                Role.ADMINISTRATOR,
                "Administrator",
                "Manages access, users and maintenance operations");
        ensureSystemRole(
                Role.MANAGER,
                "Manager",
                "Reviews maintenance operations and reporting");
    }

    private void ensureSystemRole(Role code, String name, String description) {
        if (systemRoleRepository.findByCode(code).isEmpty()) {
            systemRoleRepository.save(new SystemRole(code, name, description));
        }
    }

    /**
     * Moves the legacy all-in-one app_users fields into normalized child
     * tables. The account ID is preserved so existing ticket foreign keys stay
     * valid. This method is intentionally idempotent for interrupted local
     * development migrations.
     */
    private void migrateLegacyUserTable() {
        if (!columnExists("APP_USERS", "PASSWORD")) {
            return;
        }

        List<LegacyUserRow> legacyUsers = jdbcTemplate.query(
                """
                SELECT id, name, email, password, identity_type, role,
                       contact_information, profile_details,
                       avatar_data, avatar_content_type
                FROM app_users
                """,
                this::mapLegacyUser);

        for (LegacyUserRow legacy : legacyUsers) {
            migrateProfile(legacy);
            migrateIdentity(legacy);
            migrateRoleAssignment(legacy);

            String passwordHash = isBcryptHash(legacy.password())
                    ? legacy.password()
                    : passwordEncoder.encode(legacy.password());
            jdbcTemplate.update(
                    """
                    UPDATE app_users
                    SET password_hash = ?,
                        account_status = 'ACTIVE',
                        created_at = COALESCE(created_at, CURRENT_TIMESTAMP),
                        updated_at = COALESCE(updated_at, CURRENT_TIMESTAMP)
                    WHERE id = ?
                    """,
                    passwordHash,
                    legacy.id());
        }

        verifyLegacyMigration(legacyUsers.size());

        jdbcTemplate.execute("ALTER TABLE app_users DROP COLUMN IF EXISTS avatar_content_type");
        jdbcTemplate.execute("ALTER TABLE app_users DROP COLUMN IF EXISTS avatar_data");
        jdbcTemplate.execute("ALTER TABLE app_users DROP COLUMN IF EXISTS contact_information");
        jdbcTemplate.execute("ALTER TABLE app_users DROP COLUMN IF EXISTS profile_details");
        jdbcTemplate.execute("ALTER TABLE app_users DROP COLUMN IF EXISTS identity_type");
        jdbcTemplate.execute("ALTER TABLE app_users DROP COLUMN IF EXISTS role");
        jdbcTemplate.execute("ALTER TABLE app_users DROP COLUMN IF EXISTS name");
        jdbcTemplate.execute("ALTER TABLE app_users DROP COLUMN IF EXISTS password");
    }

    private LegacyUserRow mapLegacyUser(ResultSet resultSet, int rowNumber) throws SQLException {
        return new LegacyUserRow(
                resultSet.getLong("id"),
                resultSet.getString("name"),
                resultSet.getString("email"),
                resultSet.getString("password"),
                IdentityType.valueOf(resultSet.getString("identity_type")),
                Role.valueOf(resultSet.getString("role")),
                resultSet.getString("contact_information"),
                resultSet.getString("profile_details"),
                resultSet.getBytes("avatar_data"),
                resultSet.getString("avatar_content_type"));
    }

    private void migrateProfile(LegacyUserRow legacy) {
        if (count("SELECT COUNT(*) FROM user_profiles WHERE user_id = ?", legacy.id()) > 0) {
            return;
        }

        jdbcTemplate.update(
                """
                INSERT INTO user_profiles(
                    user_id, name, contact_information, profile_details,
                    avatar_data, avatar_content_type
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                legacy.id(),
                legacy.name(),
                legacy.contactInformation(),
                legacy.profileDetails(),
                legacy.avatarData(),
                legacy.avatarContentType());
    }

    private void migrateIdentity(LegacyUserRow legacy) {
        if (count("SELECT COUNT(*) FROM campus_identities WHERE user_id = ?", legacy.id()) > 0) {
            return;
        }

        jdbcTemplate.update(
                "INSERT INTO campus_identities(user_id, identity_type) VALUES (?, ?)",
                legacy.id(),
                legacy.identityType().name());
    }

    private void migrateRoleAssignment(LegacyUserRow legacy) {
        if (count(
                "SELECT COUNT(*) FROM user_role_assignments WHERE user_id = ? AND is_active = TRUE",
                legacy.id()) > 0) {
            return;
        }

        Long roleId = systemRoleRepository.findByCode(legacy.role())
                .orElseThrow(() -> new IllegalStateException(
                        "Missing system role during migration: " + legacy.role()))
                .getId();
        jdbcTemplate.update(
                """
                INSERT INTO user_role_assignments(
                    user_id, role_id, assigned_by, assigned_at, revoked_at, is_active
                ) VALUES (?, ?, NULL, CURRENT_TIMESTAMP, NULL, TRUE)
                """,
                legacy.id(),
                roleId);
    }

    private void verifyLegacyMigration(int expectedAccounts) {
        long profiles = count("SELECT COUNT(*) FROM user_profiles");
        long identities = count("SELECT COUNT(*) FROM campus_identities");
        long activeAssignments = count(
                "SELECT COUNT(DISTINCT user_id) FROM user_role_assignments WHERE is_active = TRUE");
        long hashedAccounts = count(
                "SELECT COUNT(*) FROM app_users WHERE password_hash IS NOT NULL");

        if (profiles < expectedAccounts
                || identities < expectedAccounts
                || activeAssignments < expectedAccounts
                || hashedAccounts < expectedAccounts) {
            throw new IllegalStateException("Legacy user migration did not produce all required relational records");
        }
    }

    private void enforceAccountConstraints() {
        jdbcTemplate.update(
                "UPDATE app_users SET account_status = 'ACTIVE' WHERE account_status IS NULL");
        jdbcTemplate.update(
                "UPDATE app_users SET created_at = CURRENT_TIMESTAMP WHERE created_at IS NULL");
        jdbcTemplate.update(
                "UPDATE app_users SET updated_at = CURRENT_TIMESTAMP WHERE updated_at IS NULL");
        jdbcTemplate.execute("ALTER TABLE app_users ALTER COLUMN password_hash SET NOT NULL");
        jdbcTemplate.execute("ALTER TABLE app_users ALTER COLUMN account_status SET NOT NULL");
        jdbcTemplate.execute("ALTER TABLE app_users ALTER COLUMN created_at SET NOT NULL");
        jdbcTemplate.execute("ALTER TABLE app_users ALTER COLUMN updated_at SET NOT NULL");
    }

    private void ensureRelationalUserData() {
        SystemRole requesterRole = systemRoleRepository.findByCode(Role.GENERAL_USER)
                .orElseThrow();

        for (User user : userRepository.findAll()) {
            if (user.getName() == null) {
                user.setName(user.getEmail());
            }
            if (user.getIdentityType() == null) {
                user.changeIdentityType(IdentityType.STUDENT, null);
            }
            if (user.getRole() == null) {
                user.assignRole(requesterRole, null);
            }
            userRepository.save(user);
        }
    }

    /** Preserve supported grants and the audit trail when retiring the Manager role. */
    private void migrateManagerAssignments() {
        SystemRole requesterRole = systemRoleRepository.findByCode(Role.GENERAL_USER)
                .orElseThrow();
        for (User user : userRepository.findAll()) {
            if (!user.hasRole(Role.MANAGER)) {
                continue;
            }
            Set<Role> retained = user.getRoles().stream()
                    .filter(Role::isSelectable)
                    .collect(Collectors.toSet());
            if (retained.isEmpty()) {
                user.assignRole(requesterRole, null);
                retained = Set.of(Role.GENERAL_USER);
            }
            user.revokeRolesExcept(retained);
            userRepository.save(user);
        }
    }

    private void ensureAdministrator() {
        SystemRole administratorRole = systemRoleRepository.findByCode(Role.ADMINISTRATOR)
                .orElseThrow();
        User administrator = userRepository.findByEmailIgnoreCase(ADMIN_EMAIL)
                .orElseGet(() -> createAdministrator());

        if (administrator.getIdentityType() != IdentityType.CAMPUS_STAFF) {
            administrator.changeIdentityType(IdentityType.CAMPUS_STAFF, administrator);
        }
        administrator.assignRole(administratorRole, administrator.getId() == null ? null : administrator);
        userRepository.save(administrator);
    }

    private User createAdministrator() {
        if (bootstrapAdminPassword == null || bootstrapAdminPassword.isBlank()) {
            throw new IllegalStateException(
                    "CAMPUSFIX_ADMIN_PASSWORD must be set before creating the initial administrator");
        }

        return new User(
                ADMIN_NAME,
                ADMIN_EMAIL,
                passwordEncoder.encode(bootstrapAdminPassword),
                IdentityType.CAMPUS_STAFF);
    }

    private void migrateTicketStatuses() {
        jdbcTemplate.execute("""
                ALTER TABLE tickets ALTER COLUMN operational_priority
                ENUM('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')
                """);
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
    }

    private void backfillTicketHistory() {
        for (Ticket ticket : ticketRepository.findAll()) {
            if (!ticketHistoryRepository.existsByTicket_Id(ticket.getId())) {
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

    private boolean columnExists(String tableName, String columnName) {
        return count(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'PUBLIC'
                  AND table_name = ?
                  AND column_name = ?
                """,
                tableName,
                columnName) > 0;
    }

    private long count(String sql, Object... parameters) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, parameters);
        return value == null ? 0 : value;
    }

    private boolean isBcryptHash(String value) {
        return value != null && value.matches("^\\$2[aby]\\$.*");
    }

    private record LegacyUserRow(
            Long id,
            String name,
            String email,
            String password,
            IdentityType identityType,
            Role role,
            String contactInformation,
            String profileDetails,
            byte[] avatarData,
            String avatarContentType) {
    }
}
