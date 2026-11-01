package com.campusfix;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:student-rating-ban;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "campusfix.bootstrap-admin.password=integration-test-admin-password",
        "campusfix.registration.email-verification.required=false"
})
class StudentRatingBanIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void administratorCanSuspendStudentRatingsWithoutAnEndDate() throws Exception {
        MvcResult registration = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", "Rating Student",
                                "email", "rating.student@test.local", "password", "password123"))))
                .andExpect(status().isCreated()).andReturn();
        long studentId = json.readTree(registration.getResponse().getContentAsString())
                .path("id").asLong();

        MockHttpSession administrator = login("admin@campusfix.edu",
                "integration-test-admin-password");
        String banPath = "/api/admin/reviews/students/" + studentId + "/ban";
        MvcResult before = mvc.perform(get(banPath).session(administrator))
                .andExpect(status().isOk()).andReturn();
        assertFalse(json.readTree(before.getResponse().getContentAsString())
                .path("banned").asBoolean());

        MvcResult suspended = mvc.perform(post(banPath).session(administrator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("reason", "1233"))))
                .andExpect(status().isOk()).andReturn();
        JsonNode ban = json.readTree(suspended.getResponse().getContentAsString());
        assertTrue(ban.path("banned").asBoolean());
        assertTrue(ban.path("expiresAt").isNull());

        MvcResult after = mvc.perform(get(banPath).session(administrator))
                .andExpect(status().isOk()).andReturn();
        assertTrue(json.readTree(after.getResponse().getContentAsString())
                .path("banned").asBoolean());

        MockHttpSession student = login("rating.student@test.local", "password123");
        MvcResult inbox = mvc.perform(get("/api/notifications").session(student))
                .andExpect(status().isOk()).andReturn();
        assertTrue(inbox.getResponse().getContentAsString().contains("REVIEW_BANNED"));
    }

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }
}
