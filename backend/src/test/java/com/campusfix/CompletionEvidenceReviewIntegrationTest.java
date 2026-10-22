package com.campusfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.datasource.url=jdbc:h2:mem:completion-review;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "campusfix.bootstrap-admin.password=integration-test-admin-password",
        "campusfix.registration.email-verification.required=false"
})
class CompletionEvidenceReviewIntegrationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void uploadedRepairPhotoAllowsCompletionThenStudentRating() throws Exception {
        long studentId = register("Review Student", "repair.student@test.local");
        long technicianId = register("Repair Technician", "repair.technician@test.local");
        MockHttpSession administrator = login("admin@campusfix.edu", "integration-test-admin-password");
        mvc.perform(put("/api/admin/users/{id}", technicianId).session(administrator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("identityType", "CAMPUS_STAFF",
                                "roles", new String[] {"MAINTENANCE_STAFF"}))))
                .andExpect(status().isOk());
        MockHttpSession student = login("repair.student@test.local", "password123");
        MockHttpSession technician = login("repair.technician@test.local", "password123");

        MvcResult created = mvc.perform(post("/api/tickets").session(student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("title", "Broken light", "description", "Replace room light",
                                "category", "OTHER", "location", "Campus room", "urgency", "MEDIUM"))))
                .andExpect(status().isCreated()).andReturn();
        long ticketId = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        assertTrue(ticketId > 0 && studentId > 0);
        MockMultipartFile invalidMimeImage = new MockMultipartFile(
                "image", "repair.jpg", "image/" + "x".repeat(100), new byte[] {1});
        mvc.perform(multipart("/api/tickets/{id}/images", ticketId)
                        .file(invalidMimeImage).session(student))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/tickets/{id}/assignment", ticketId).session(administrator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("technicianId", technicianId))))
                .andExpect(status().isOk());
        updateStatus(ticketId, technician, "IN_PROGRESS");
        mvc.perform(multipart("/api/tickets/{id}/completion-evidence", ticketId)
                        .file(invalidMimeImage).param("repairNotes", "Repair completed")
                        .session(technician))
                .andExpect(status().isBadRequest());

        byte[] image = new byte[700_000];
        image[0] = (byte) 0xff;
        image[1] = (byte) 0xd8;
        image[image.length - 2] = (byte) 0xff;
        image[image.length - 1] = (byte) 0xd9;
        MockMultipartFile photo = new MockMultipartFile(
                "image", "repair.jpg", "image/jpeg", image);
        MvcResult uploaded = mvc.perform(multipart("/api/tickets/{id}/completion-evidence", ticketId)
                        .file(photo).param("repairNotes", "Replaced the light fixture")
                        .session(technician))
                .andExpect(status().isCreated()).andReturn();
        long evidenceId = json.readTree(uploaded.getResponse().getContentAsString()).path("id").asLong();
        assertTrue(evidenceId > 0);
        String longNotes = "x".repeat(1500);
        MvcResult longEvidence = mvc.perform(multipart("/api/tickets/{id}/completion-evidence", ticketId)
                        .file(photo).param("repairNotes", longNotes).session(technician))
                .andExpect(status().isCreated()).andReturn();
        assertEquals(longNotes, json.readTree(longEvidence.getResponse().getContentAsString())
                .path("repairNotes").asText());
        mvc.perform(multipart("/api/tickets/{id}/completion-evidence", ticketId)
                        .file(photo).param("repairNotes", "x".repeat(2001)).session(technician))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tickets/{id}/completion-evidence", ticketId).session(student))
                .andExpect(status().isOk());

        updateStatus(ticketId, technician, "COMPLETED");
        MvcResult rating = mvc.perform(put("/api/reviews/tickets/{id}/mine", ticketId)
                        .session(student).contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("score", 5, "comment", "Fixed quickly"))))
                .andExpect(status().isOk()).andReturn();
        assertEquals(5, json.readTree(rating.getResponse().getContentAsString()).path("score").asInt());
    }

    private long register(String name, String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("name", name, "email", email, "password", "password123"))))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
    }

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private void updateStatus(long ticketId, MockHttpSession technician, String next) throws Exception {
        mvc.perform(put("/api/tickets/{id}/status", ticketId).session(technician)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("status", next))))
                .andExpect(status().isOk());
    }

    private String body(Object value) throws Exception {
        return json.writeValueAsString(value);
    }
}
