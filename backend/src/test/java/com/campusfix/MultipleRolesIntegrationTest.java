package com.campusfix;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
    void combinedRolesPreserveOwnershipAssignmentBoundariesAndRevokeImmediately() throws Exception {
        HttpClient admin = login("admin@campusfix.edu", "integration-test-admin-password");
        long dualId = register("dual@test.local");
        long otherId = register("other@test.local");
        HttpClient dual = login("dual@test.local", "password123");
        HttpClient other = login("other@test.local", "password123");
        String accessPath = "/api/admin/users/" + dualId;
        assertEquals(403, send(dual, "PUT", accessPath, roles("ADMINISTRATOR")).statusCode());
        assertEquals(200, send(admin, "PUT", accessPath, roles("GENERAL_USER", "MAINTENANCE_STAFF")).statusCode());
        assertTrue(get(dual, "/api/users/me").body().contains("\"roles\":[\"GENERAL_USER\",\"MAINTENANCE_STAFF\"]"));
        assertTrue(get(admin, "/api/tickets/technicians").body().contains("dual@test.local"));
        long own = ticket(dual, "Owned ticket");
        long assigned = ticket(other, "Assigned ticket");
        long unrelated = ticket(other, "Unrelated ticket");
        assertEquals(200, send(admin, "POST", "/api/tickets/" + assigned + "/assignment",
                "{\"technicianId\":" + dualId + "}").statusCode());
        assertEquals(200, send(admin, "POST", "/api/tickets/" + own + "/assignment",
                "{\"technicianId\":" + dualId + "}").statusCode());
        String visible = get(dual, "/api/tickets").body();
        assertEquals(Set.of(own, assigned), ids(visible));
        assertEquals(2, Pattern.compile("\"id\":").matcher(visible).results().count());
        assertEquals(403, get(dual, "/api/tickets/" + unrelated).statusCode());
        assertEquals(403, get(dual, "/api/tickets/" + unrelated + "/history").statusCode());
        assertEquals(403, send(dual, "POST", "/api/tickets/" + assigned + "/cancel", "{}").statusCode());
        assertEquals(200, send(dual, "PUT", "/api/tickets/" + assigned + "/status",
                "{\"status\":\"IN_PROGRESS\"}").statusCode());

        assertEquals(200, send(admin, "PUT", accessPath, roles("GENERAL_USER")).statusCode());
        assertEquals(Set.of(own), ids(get(dual, "/api/tickets").body()));
        assertEquals(403, get(dual, "/api/tickets/" + assigned).statusCode());
        assertEquals(403, send(dual, "PUT", "/api/tickets/" + assigned + "/status",
                "{\"status\":\"COMPLETED\"}").statusCode());
        assertFalse(get(admin, "/api/tickets/technicians").body().contains("dual@test.local"));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_role_assignments WHERE user_id = ? AND is_active = FALSE AND revoked_at IS NOT NULL",
                Integer.class, dualId));

        // Manager plus technician can read everything but only work on their own assignments.
        assertEquals(200, send(admin, "PUT", accessPath, roles("MANAGER", "MAINTENANCE_STAFF")).statusCode());
        assertEquals(Set.of(own, assigned, unrelated), ids(get(dual, "/api/tickets").body()));
        assertEquals(200, get(dual, "/api/tickets/priority-summary").statusCode());
        assertEquals(403, send(dual, "PUT", "/api/tickets/" + unrelated + "/priority",
                "{\"priority\":\"HIGH\"}").statusCode());
        assertEquals(403, send(dual, "PUT", "/api/tickets/" + unrelated + "/status",
                "{\"status\":\"IN_PROGRESS\"}").statusCode());
        assertEquals(200, send(dual, "PUT", "/api/tickets/" + assigned + "/status",
                "{\"status\":\"COMPLETED\"}").statusCode());

        // Combined admin access does not hide technician permissions or automatically grant requester access.
        assertEquals(200, send(admin, "PUT", accessPath, roles("ADMINISTRATOR", "MAINTENANCE_STAFF")).statusCode());
        assertEquals(200, get(dual, "/api/admin/users").statusCode());
        assertEquals(200, send(dual, "PUT", "/api/tickets/" + own + "/status",
                "{\"status\":\"IN_PROGRESS\"}").statusCode());
        assertEquals(403, send(dual, "POST", "/api/tickets", ticketBody("Forbidden submission")).statusCode());
        assertEquals(200, send(admin, "PUT", accessPath, roles("GENERAL_USER")).statusCode());
        assertEquals(403, get(dual, "/api/admin/users").statusCode());
        assertTrue(otherId > 0);
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
        assertEquals(401, send(client(), "PUT", path, roles("MANAGER")).statusCode());
        assertEquals(200, send(admin, "PUT", path, roles("GENERAL_USER", "MANAGER")).statusCode());
        int records = jdbc.queryForObject("SELECT COUNT(*) FROM user_role_assignments WHERE user_id = ?", Integer.class, userId);
        assertEquals(200, send(admin, "PUT", path, roles("GENERAL_USER", "MANAGER")).statusCode());
        assertEquals(records, jdbc.queryForObject("SELECT COUNT(*) FROM user_role_assignments WHERE user_id = ?", Integer.class, userId));
        assertEquals(200, send(admin, "PUT", path,
                "{\"identityType\":\"CAMPUS_STAFF\",\"role\":\"MAINTENANCE_STAFF\"}").statusCode());
        HttpClient user = login("validation@test.local", "password123");
        assertTrue(get(user, "/api/users/me").body().contains("\"roles\":[\"MAINTENANCE_STAFF\"]"));
        assertEquals(200, send(admin, "PUT", path + "/role", "{\"role\":\"GENERAL_USER\"}").statusCode());
        assertTrue(get(user, "/api/users/me").body().contains("\"roles\":[\"GENERAL_USER\"]"));
        assertEquals(409, send(admin, "PUT", "/api/admin/users/" + adminId, roles("MANAGER")).statusCode());
        assertEquals(409, send(admin, "PUT", "/api/admin/users/" + adminId + "/role", "{\"role\":\"MANAGER\"}").statusCode());
        assertEquals(200, send(admin, "PUT", "/api/admin/users/" + adminId,
                roles("ADMINISTRATOR", "GENERAL_USER", "MAINTENANCE_STAFF")).statusCode());
        initializer.run();
        String profile = get(admin, "/api/users/me").body();
        assertTrue(profile.contains("\"roles\":[\"GENERAL_USER\",\"MAINTENANCE_STAFF\",\"ADMINISTRATOR\"]"));
        assertEquals(200, get(admin, "/api/admin/users").statusCode());
        jdbc.update("UPDATE app_users SET account_status = 'DISABLED' WHERE id = ?", userId);
        assertEquals(401, get(user, "/api/users/me").statusCode());
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
        var response = send(client, "POST", "/api/tickets", ticketBody(title));
        assertEquals(201, response.statusCode(), response.body());
        return id(response.body());
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
        return send(client, "GET", path, "");
    }

    private HttpResponse<String> send(HttpClient client, String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private Set<Long> ids(String body) {
        return Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(body).results()
                .map(match -> Long.valueOf(match.group(1))).collect(Collectors.toSet());
    }

    private long id(String body) { return ids(body).iterator().next(); }
}
