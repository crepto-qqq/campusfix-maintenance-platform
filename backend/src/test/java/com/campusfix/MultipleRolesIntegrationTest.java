package com.campusfix;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.campusfix.config.DataInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:multiple-roles;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "campusfix.bootstrap-admin.password=integration-test-admin-password",
        "campusfix.registration.email-verification.required=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class MultipleRolesIntegrationTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataInitializer initializer;

    @Test
    void activeRoleSeparatesOwnAssignedAndAdministrativeWorkAndRevokesImmediately() throws Exception {
        HttpClient admin = login("admin@campusfix.edu", "integration-test-admin-password");
        long dualId = register("dual@test.local");
        register("other@test.local");
        HttpClient dual = login("dual@test.local", "password123");
        HttpClient other = login("other@test.local", "password123");
        String accessPath = "/api/admin/users/" + dualId;
        assertEquals(403, send(dual, "PUT", accessPath, roles("ADMINISTRATOR")).statusCode());
        assertEquals(200, send(admin, "PUT", accessPath, roles("GENERAL_USER", "MAINTENANCE_STAFF")).statusCode());
        assertTrue(get(dual, "/api/users/me").body().contains("\"activeRole\":null"));
        assertEquals(409, get(dual, "/api/tickets").statusCode());
        assertEquals(403, selectRole(dual, "ADMINISTRATOR").statusCode());
        assertEquals(403, selectRole(dual, "MANAGER").statusCode());
        assertEquals(200, selectRole(dual, "GENERAL_USER").statusCode());
        assertTrue(get(admin, "/api/tickets/technicians").body().contains("dual@test.local"));
        long own = ticket(dual, "Owned ticket", "GENERAL_USER");
        long ownAndAssigned = ticket(dual, "Owned and assigned ticket", "GENERAL_USER");
        long assigned = ticket(other, "Assigned ticket");
        long unrelated = ticket(other, "Unrelated ticket");
        assertEquals(204, uploadTicketImage(other, assigned).statusCode());
        long assignedImageId = Pattern.compile("\"imageIds\":\\[(\\d+)")
                .matcher(get(other, "/api/tickets/" + assigned).body()).results()
                .map(match -> Long.valueOf(match.group(1))).findFirst().orElseThrow();
        assertEquals(200, send(admin, "POST", "/api/tickets/" + assigned + "/assignment",
                "{\"technicianId\":" + dualId + "}").statusCode());
        assertEquals(200, send(admin, "POST", "/api/tickets/" + ownAndAssigned + "/assignment",
                "{\"technicianId\":" + dualId + "}").statusCode());
        assertEquals(Set.of(own, ownAndAssigned), ids(get(dual, "/api/tickets", "GENERAL_USER").body()));
        assertEquals(403, get(dual, "/api/tickets/" + assigned, "GENERAL_USER").statusCode());
        assertEquals(403, get(dual,
                "/api/tickets/" + assigned + "/images/" + assignedImageId + "?activeRole=GENERAL_USER")
                .statusCode());
        assertEquals(403, get(dual, "/api/tickets/" + assigned + "/history", "GENERAL_USER").statusCode());
        assertEquals(403, send(dual, "PUT", "/api/tickets/" + assigned + "/status",
                "{\"status\":\"IN_PROGRESS\"}", "GENERAL_USER").statusCode());
        assertEquals(403, get(dual, "/api/admin/users", "GENERAL_USER").statusCode());

        assertEquals(200, selectRole(dual, "MAINTENANCE_STAFF").statusCode());
        assertEquals(Set.of(ownAndAssigned, assigned), ids(get(dual, "/api/tickets", "MAINTENANCE_STAFF").body()));
        assertEquals(200, get(dual,
                "/api/tickets/" + assigned + "/images/" + assignedImageId + "?activeRole=MAINTENANCE_STAFF")
                .statusCode());
        assertEquals(409, get(dual, "/api/tickets/" + assigned + "/images/" + assignedImageId).statusCode());
        assertEquals(403, get(dual, "/api/tickets/" + own, "MAINTENANCE_STAFF").statusCode());
        assertEquals(403, send(dual, "POST", "/api/tickets", ticketBody("Forbidden submission"),
                "MAINTENANCE_STAFF").statusCode());
        assertEquals(403, send(dual, "POST", "/api/tickets/" + ownAndAssigned + "/cancel", "{}",
                "MAINTENANCE_STAFF").statusCode());
        assertEquals(403, get(dual, "/api/admin/users", "MAINTENANCE_STAFF").statusCode());
        assertEquals(200, send(dual, "PUT", "/api/tickets/" + assigned + "/status",
                "{\"status\":\"IN_PROGRESS\"}", "MAINTENANCE_STAFF").statusCode());

        // The role is scoped to the request, so two clients (or tabs) can use different modes.
        HttpClient secondTab = login("dual@test.local", "password123");
        assertEquals(Set.of(own, ownAndAssigned), ids(get(secondTab, "/api/tickets", "GENERAL_USER").body()));
        assertEquals(Set.of(ownAndAssigned, assigned), ids(get(dual, "/api/tickets", "MAINTENANCE_STAFF").body()));
        assertEquals(Set.of(own, ownAndAssigned), ids(get(dual, "/api/tickets", "GENERAL_USER").body()));

        assertEquals(200, send(admin, "PUT", accessPath,
                roles("GENERAL_USER", "MAINTENANCE_STAFF", "ADMINISTRATOR")).statusCode());
        assertEquals(200, selectRole(dual, "ADMINISTRATOR").statusCode());
        assertEquals(Set.of(own, ownAndAssigned, assigned, unrelated),
                ids(get(dual, "/api/tickets", "ADMINISTRATOR").body()));
        assertEquals(200, get(dual, "/api/admin/users", "ADMINISTRATOR").statusCode());
        assertEquals(403, send(dual, "POST", "/api/tickets", ticketBody("No admin submission"),
                "ADMINISTRATOR").statusCode());
        assertEquals(403, send(dual, "PUT", "/api/tickets/" + ownAndAssigned + "/status",
                "{\"status\":\"IN_PROGRESS\"}", "ADMINISTRATOR").statusCode());

        assertEquals(200, send(admin, "PUT", accessPath, roles("GENERAL_USER", "ADMINISTRATOR")).statusCode());
        assertEquals(409, get(dual, "/api/tickets", "MAINTENANCE_STAFF").statusCode());
        assertEquals(403, selectRole(dual, "MAINTENANCE_STAFF").statusCode());
        assertEquals(200, get(dual, "/api/tickets", "GENERAL_USER").statusCode());
        assertEquals(200, get(dual, "/api/admin/users", "ADMINISTRATOR").statusCode());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_role_assignments WHERE user_id = ? AND is_active = FALSE AND revoked_at IS NOT NULL",
                Integer.class, dualId));

        assertEquals(200, send(admin, "PUT", accessPath, roles("GENERAL_USER")).statusCode());
        assertTrue(get(dual, "/api/users/me", "ADMINISTRATOR").body().contains("\"activeRole\":\"GENERAL_USER\""));
        assertEquals(409, get(dual, "/api/tickets", "MAINTENANCE_STAFF").statusCode());
        assertEquals(409, get(dual, "/api/tickets", "ADMINISTRATOR").statusCode());
        assertEquals(Set.of(own, ownAndAssigned), ids(get(dual, "/api/tickets").body()));
        assertEquals(Set.of(own, ownAndAssigned), ids(get(dual, "/api/tickets", "GENERAL_USER").body()));
        assertEquals(409, get(dual, "/api/admin/users", "ADMINISTRATOR").statusCode());
        assertFalse(get(admin, "/api/tickets/technicians").body().contains("dual@test.local"));
        jdbc.update("UPDATE app_users SET account_status = 'DISABLED' WHERE id = ?", dualId);
        assertEquals(401, get(dual, "/api/users/me").statusCode());
    }

    @Test
    void validatesRoleSetsProtectsLastAdminAndPreservesLegacyRequestsAndBootstrap() throws Exception {
        HttpClient admin = login("admin@campusfix.edu", "integration-test-admin-password");
        long adminId = id(get(admin, "/api/users/me").body());
        long userId = register("validation@test.local");
        String path = "/api/admin/users/" + userId;
        for (String body : new String[] {
                "{\"identityType\":\"STUDENT\"}",
                "{\"identityType\":\"STUDENT\",\"roles\":[]}",
                "{\"identityType\":\"STUDENT\",\"roles\":[null]}",
                "{\"identityType\":\"STUDENT\",\"roles\":[\"INVALID\"]}",
                "{\"identityType\":\"STUDENT\",\"role\":\"MANAGER\",\"roles\":[\"GENERAL_USER\"]}"
        }) assertEquals(400, send(admin, "PUT", path, body).statusCode(), body);
        assertEquals(401, send(client(), "PUT", path, roles("GENERAL_USER")).statusCode());
        assertEquals(400, send(admin, "PUT", path, roles("MANAGER")).statusCode());
        assertEquals(400, send(admin, "PUT", path, roles("GENERAL_USER", "MANAGER")).statusCode());
        assertEquals(400, send(admin, "PUT", path + "/role", "{\"role\":\"MANAGER\"}").statusCode());
        assertEquals(200, send(admin, "PUT", path, roles("GENERAL_USER", "MAINTENANCE_STAFF")).statusCode());
        int records = jdbc.queryForObject("SELECT COUNT(*) FROM user_role_assignments WHERE user_id = ?", Integer.class, userId);
        assertEquals(200, send(admin, "PUT", path, roles("GENERAL_USER", "MAINTENANCE_STAFF")).statusCode());
        assertEquals(records, jdbc.queryForObject("SELECT COUNT(*) FROM user_role_assignments WHERE user_id = ?", Integer.class, userId));
        assertEquals(200, send(admin, "PUT", path,
                "{\"identityType\":\"CAMPUS_STAFF\",\"role\":\"MAINTENANCE_STAFF\"}").statusCode());
        HttpClient user = login("validation@test.local", "password123");
        assertTrue(get(user, "/api/users/me").body().contains("\"roles\":[\"MAINTENANCE_STAFF\"]"));
        assertEquals(200, send(admin, "PUT", path + "/role", "{\"role\":\"GENERAL_USER\"}").statusCode());
        assertTrue(get(user, "/api/users/me").body().contains("\"roles\":[\"GENERAL_USER\"]"));
        assertEquals(409, send(admin, "PUT", "/api/admin/users/" + adminId, roles("GENERAL_USER")).statusCode());
        assertEquals(409, send(admin, "PUT", "/api/admin/users/" + adminId + "/role", "{\"role\":\"GENERAL_USER\"}").statusCode());
        assertEquals(200, send(admin, "PUT", "/api/admin/users/" + adminId,
                roles("ADMINISTRATOR", "GENERAL_USER", "MAINTENANCE_STAFF")).statusCode());
        initializer.run();
        String profile = get(admin, "/api/users/me").body();
        assertTrue(profile.contains("\"roles\":[\"GENERAL_USER\",\"MAINTENANCE_STAFF\",\"ADMINISTRATOR\"]"));
        assertEquals(409, get(admin, "/api/admin/users").statusCode());
        assertEquals(200, get(admin, "/api/admin/users", "ADMINISTRATOR").statusCode());
        jdbc.update("UPDATE app_users SET account_status = 'DISABLED' WHERE id = ?", userId);
        assertEquals(401, get(user, "/api/users/me").statusCode());
    }

    @Test
    void managerMigrationKeepsSupportedGrantsAndIsIdempotent() throws Exception {
        HttpClient admin = login("admin@campusfix.edu", "integration-test-admin-password");
        long managerOnlyId = register("manager-only@test.local");
        long mixedId = register("manager-mixed@test.local");
        assertEquals(200, send(admin, "PUT", "/api/admin/users/" + mixedId,
                roles("GENERAL_USER", "MAINTENANCE_STAFF")).statusCode());

        // Simulate pre-upgrade rows in the isolated in-memory database.
        jdbc.update("""
                UPDATE user_role_assignments SET is_active = FALSE, revoked_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND role_id = (SELECT id FROM system_roles WHERE code = 'GENERAL_USER')
                  AND is_active = TRUE
                """, managerOnlyId);
        for (long id : new long[] {managerOnlyId, mixedId}) {
            jdbc.update("""
                    INSERT INTO user_role_assignments
                        (user_id, role_id, assigned_by, assigned_at, revoked_at, is_active)
                    SELECT ?, id, NULL, CURRENT_TIMESTAMP, NULL, TRUE
                    FROM system_roles WHERE code = 'MANAGER'
                    """, id);
        }

        initializer.run();
        HttpClient managerOnly = login("manager-only@test.local", "password123");
        HttpClient mixed = login("manager-mixed@test.local", "password123");
        String managerOnlyProfile = get(managerOnly, "/api/users/me").body();
        String mixedProfile = get(mixed, "/api/users/me").body();
        assertTrue(managerOnlyProfile.contains("\"roles\":[\"GENERAL_USER\"]"));
        assertTrue(managerOnlyProfile.contains("\"activeRole\":\"GENERAL_USER\""));
        assertTrue(mixedProfile.contains("\"roles\":[\"GENERAL_USER\",\"MAINTENANCE_STAFF\"]"));
        assertTrue(mixedProfile.contains("\"activeRole\":null"));
        assertEquals(0, activeManagerGrants(managerOnlyId));
        assertEquals(0, activeManagerGrants(mixedId));
        assertEquals(1, revokedManagerGrants(managerOnlyId));
        assertEquals(1, revokedManagerGrants(mixedId));

        int managerOnlyRows = assignmentRows(managerOnlyId);
        int mixedRows = assignmentRows(mixedId);
        initializer.run();
        assertEquals(managerOnlyRows, assignmentRows(managerOnlyId));
        assertEquals(mixedRows, assignmentRows(mixedId));
        assertEquals(1, revokedManagerGrants(managerOnlyId));
        assertEquals(1, revokedManagerGrants(mixedId));
    }

    @Test
    void simultaneousAdminRevocationsCannotRemoveEveryAdministrator() throws Exception {
        HttpClient first = login("admin@campusfix.edu", "integration-test-admin-password");
        long firstId = id(get(first, "/api/users/me").body());
        long secondId = register("second-admin@test.local");
        assertEquals(200, send(first, "PUT", "/api/admin/users/" + secondId, roles("ADMINISTRATOR")).statusCode());
        HttpClient second = login("second-admin@test.local", "password123");
        var one = CompletableFuture.supplyAsync(() -> demote(first, firstId));
        var two = CompletableFuture.supplyAsync(() -> demote(second, secondId));
        assertEquals(Set.of(200, 409), Set.of(one.get(), two.get()));
        assertEquals(1L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_role_assignments a JOIN system_roles r ON a.role_id = r.id
                WHERE a.is_active = TRUE AND r.code = 'ADMINISTRATOR'
                """, Long.class));
    }

    private int demote(HttpClient client, long id) {
        try { return send(client, "PUT", "/api/admin/users/" + id, roles("GENERAL_USER")).statusCode(); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private int assignmentRows(long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_role_assignments WHERE user_id = ?",
                Integer.class, userId);
    }

    private int activeManagerGrants(long userId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_role_assignments a JOIN system_roles r ON a.role_id = r.id
                WHERE a.user_id = ? AND a.is_active = TRUE AND r.code = 'MANAGER'
                """, Integer.class, userId);
    }

    private int revokedManagerGrants(long userId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM user_role_assignments a JOIN system_roles r ON a.role_id = r.id
                WHERE a.user_id = ? AND a.is_active = FALSE AND a.revoked_at IS NOT NULL
                    AND r.code = 'MANAGER'
                """, Integer.class, userId);
    }

    private String roles(String... roles) {
        return "{\"identityType\":\"CAMPUS_STAFF\",\"roles\":[" +
                java.util.Arrays.stream(roles).map(role -> "\"" + role + "\"").collect(Collectors.joining(",")) + "]}";
    }

    private long register(String email) throws Exception {
        var response = send(client(), "POST", "/api/auth/register",
                "{\"name\":\"Role Test\",\"email\":\"" + email + "\",\"password\":\"password123\",\"roles\":[\"ADMINISTRATOR\"]}");
        assertEquals(201, response.statusCode());
        assertTrue(response.body().contains("\"roles\":[\"GENERAL_USER\"]"));
        return id(response.body());
    }

    private long ticket(HttpClient client, String title) throws Exception {
        return ticket(client, title, null);
    }

    private long ticket(HttpClient client, String title, String activeRole) throws Exception {
        var response = send(client, "POST", "/api/tickets", ticketBody(title), activeRole);
        assertEquals(201, response.statusCode(), response.body());
        return id(response.body());
    }

    private HttpResponse<String> uploadTicketImage(HttpClient client, long ticketId) throws Exception {
        String boundary = "CampusFixRoleIsolationTest";
        byte[] prefix = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"image\"; filename=\"test.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] image = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47};
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/tickets/" + ticketId + "/images"))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(
                        HttpRequest.BodyPublishers.ofByteArray(prefix),
                        HttpRequest.BodyPublishers.ofByteArray(image),
                        HttpRequest.BodyPublishers.ofByteArray(suffix)))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String ticketBody(String title) {
        return """
                {"title":"%s","description":"Multi-role acceptance test","category":"OTHER","location":"Lab","urgency":"MEDIUM"}
                """.formatted(title);
    }

    private HttpClient login(String email, String password) throws Exception {
        HttpClient client = client();
        assertEquals(200, send(client, "POST", "/api/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}").statusCode());
        return client;
    }

    private HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return get(client, path, null);
    }

    private HttpResponse<String> get(HttpClient client, String path, String activeRole) throws Exception {
        return send(client, "GET", path, "", activeRole);
    }

    private HttpResponse<String> selectRole(HttpClient client, String role) throws Exception {
        return send(client, "POST", "/api/auth/active-role", "{\"role\":\"" + role + "\"}");
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, String body) throws Exception {
        return send(client, method, path, body, null);
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, String body,
            String activeRole) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (activeRole != null) {
            request.header("X-Active-Role", activeRole);
        }
        return client.send(request.method(method, HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Set<Long> ids(String body) {
        return Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body).results()
                .map(match -> Long.valueOf(match.group(1))).collect(Collectors.toSet());
    }

    private long id(String body) { return ids(body).iterator().next(); }
}
