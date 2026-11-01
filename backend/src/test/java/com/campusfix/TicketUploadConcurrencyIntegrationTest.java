package com.campusfix;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.campusfix.ticket.Ticket;
import com.campusfix.ticket.TicketCategory;
import com.campusfix.ticket.TicketRepository;
import com.campusfix.ticket.TicketRequest;
import com.campusfix.ticket.TicketService;
import com.campusfix.ticket.TicketStatus;
import com.campusfix.ticket.UrgencyLevel;
import com.campusfix.user.IdentityType;
import com.campusfix.user.Role;
import com.campusfix.user.SystemRoleRepository;
import com.campusfix.user.User;
import com.campusfix.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ticket-upload-concurrency;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "campusfix.bootstrap-admin.password=integration-test-admin-password",
        "campusfix.registration.email-verification.required=false"
})
class TicketUploadConcurrencyIntegrationTest {
    @Autowired private TicketService tickets;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private UserRepository users;
    @Autowired private SystemRoleRepository roles;

    @Test
    void concurrentUploadsCannotExceedFiveRequestImages() throws Exception {
        User administrator = administrator();
        User owner = createUser("Image Owner", IdentityType.STUDENT, Role.GENERAL_USER, administrator);
        long ticketId = createTicket(owner);
        for (int index = 0; index < 4; index++) {
            tickets.addTicketImage(ticketId, owner.getId(), image());
        }

        BlockingImage firstImage = new BlockingImage();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<HttpStatusCode> first = executor.submit(() -> uploadImage(ticketId, owner.getId(), firstImage));
            assertTrue(firstImage.awaitRead(), "First upload did not reach the file read");

            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<HttpStatusCode> second = executor.submit(() -> {
                secondStarted.countDown();
                return uploadImage(ticketId, owner.getId(), image());
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(250, TimeUnit.MILLISECONDS),
                    "Second upload must wait for the first upload's ticket transaction");

            firstImage.release();
            assertEquals(HttpStatus.NO_CONTENT, first.get(10, TimeUnit.SECONDS));
            assertEquals(HttpStatus.BAD_REQUEST, second.get(10, TimeUnit.SECONDS));
            assertEquals(5, ticketRepository.findById(ticketId).orElseThrow().getImages().size());
        } finally {
            firstImage.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void evidenceUploadFinishesBeforeReassignmentAndOldTechnicianCannotUploadAgain() throws Exception {
        User administrator = administrator();
        User owner = createUser("Evidence Owner", IdentityType.STUDENT, Role.GENERAL_USER, administrator);
        User originalTechnician = createUser("Original Technician", IdentityType.CAMPUS_STAFF,
                Role.MAINTENANCE_STAFF, administrator);
        User newTechnician = createUser("New Technician", IdentityType.CAMPUS_STAFF,
                Role.MAINTENANCE_STAFF, administrator);
        long ticketId = createTicket(owner);
        tickets.assignTicket(ticketId, originalTechnician.getId(), administrator);
        tickets.updateTicketStatus(ticketId, TicketStatus.IN_PROGRESS, originalTechnician);

        BlockingImage evidenceImage = new BlockingImage();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> evidence = executor.submit(() -> tickets.addCompletionEvidence(
                    ticketId, originalTechnician, "Replaced the fitting", evidenceImage));
            assertTrue(evidenceImage.awaitRead(), "Evidence upload did not reach the file read");

            CountDownLatch reassignmentStarted = new CountDownLatch(1);
            Future<?> reassignment = executor.submit(() -> {
                reassignmentStarted.countDown();
                tickets.reassignTicket(ticketId, newTechnician.getId(),
                        "Assigned to another technician", administrator);
            });
            assertTrue(reassignmentStarted.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> reassignment.get(250, TimeUnit.MILLISECONDS),
                    "Reassignment must wait until the evidence upload releases the ticket lock");

            evidenceImage.release();
            evidence.get(10, TimeUnit.SECONDS);
            reassignment.get(10, TimeUnit.SECONDS);

            Ticket current = ticketRepository.findById(ticketId).orElseThrow();
            assertEquals(TicketStatus.ASSIGNED, current.getStatus());
            assertEquals(newTechnician.getId(), current.getActiveAssignment().getTechnician().getId());
            assertEquals(1, tickets.getCompletionEvidence(ticketId, owner, Role.GENERAL_USER).size());

            ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                    () -> tickets.addCompletionEvidence(ticketId, originalTechnician,
                            "Late upload", image()));
            assertEquals(HttpStatus.FORBIDDEN, rejected.getStatusCode());
        } finally {
            evidenceImage.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private HttpStatusCode uploadImage(long ticketId, long ownerId, MockMultipartFile image) {
        try {
            tickets.addTicketImage(ticketId, ownerId, image);
            return HttpStatus.NO_CONTENT;
        } catch (ResponseStatusException exception) {
            return exception.getStatusCode();
        }
    }

    private User administrator() {
        return users.findByEmailIgnoreCase("admin@campusfix.edu").orElseThrow();
    }

    private User createUser(String name, IdentityType identity, Role role, User administrator) {
        String email = "concurrency-" + UUID.randomUUID() + "@test.local";
        User user = new User(name, email, "unused-test-password-hash", identity);
        user.assignRole(roles.findByCode(role).orElseThrow(), administrator);
        return users.saveAndFlush(user);
    }

    private long createTicket(User owner) {
        Ticket ticket = tickets.createTicket(new TicketRequest(
                "Concurrency test", "Verify serialized ticket changes",
                TicketCategory.OTHER, "Campus lab", null, null, null, null,
                UrgencyLevel.MEDIUM), owner);
        return ticket.getId();
    }

    private MockMultipartFile image() {
        return new MockMultipartFile("image", "proof.png", "image/png",
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
    }

    private static final class BlockingImage extends MockMultipartFile {
        private final CountDownLatch reading = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private BlockingImage() {
            super("image", "proof.png", "image/png", new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
        }

        private boolean awaitRead() throws InterruptedException {
            return reading.await(5, TimeUnit.SECONDS);
        }

        private void release() {
            released.countDown();
        }

        @Override
        public byte[] getBytes() throws IOException {
            reading.countDown();
            try {
                if (!released.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting to release the test upload");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting to release the test upload", exception);
            }
            return super.getBytes();
        }
    }
}
