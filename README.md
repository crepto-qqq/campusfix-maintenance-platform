# CampusFix — Campus Maintenance Workflow Platform

> CampusFix 校园维修工单与协同管理平台

A full-stack web application for reporting, assigning, tracking, and completing maintenance requests across a university campus.

## Portfolio scope

This is a public, recruiter-facing summary of my contribution to a private University of Sydney team project. It documents the system and my implementation work without republishing the course repository, teammate-owned source code, local databases, or credentials.

| Item | Summary |
|---|---|
| Project context | University of Sydney ELEC5619 team project |
| My focus | End-to-end identity, access, profile, email-verification, and front-end integration |
| Front end | HTML, CSS, JavaScript |
| Back end | Java 21, Spring Boot, REST APIs |
| Persistence | JPA/Hibernate, H2 |
| Delivery | Git/GitHub, Gradle, JUnit, Jira/Scrum |

## Product problem

Campus maintenance reporting is often fragmented: users cannot easily see whether an issue has been received, administrators struggle to assign work consistently, and maintenance staff need a clear way to record progress and completion evidence.

CampusFix centralises that workflow:

1. A requester submits a maintenance ticket.
2. An administrator reviews priority and assigns staff.
3. Maintenance staff update progress and completion information.
4. The requester tracks status and provides feedback.

The wider team system implements ticket creation and retrieval, image attachments, priority handling, assignment and reassignment, status transitions, history/audit records, completion evidence, and in-app notifications.

## My implementation contribution

I developed the identity and access slice across the front end and back end rather than working only on page styling.

### Application foundation

- Helped establish the separate front-end and Spring Boot back-end development structure.
- Implemented the `User` persistence model and mapped it to H2 through JPA/Hibernate.
- Added repository, service, request-model, and controller layers for registration and account lookup.
- Connected HTML/CSS/JavaScript authentication pages to REST endpoints and configured local CORS behaviour.

### Authentication and access control

- Implemented registration, input validation, login, logout, and session-aware UI behaviour.
- Implemented the initial role entities and administrator initialisation; the wider multi-role model was subsequently extended by the team.
- Separated authentication pages and made login the default entry point.

### Profile and verification workflow

- Added user workspace/profile management and profile-picture upload.
- Integrated registration email verification through Brevo's transactional-email API.
- Kept the API key server-side through an ignored local environment file and documented safe team setup.
- Added a shared visual theme across the application pages.

The private repository history attributes 65 commits to Tian Liao. Selected evidence is listed in [docs/CONTRIBUTIONS.md](docs/CONTRIBUTIONS.md).

## Architecture

The project uses a conventional layered application design with browser, REST, service, persistence, and external-email boundaries. See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Skills demonstrated

- Java/Spring Boot REST API development
- JPA/Hibernate entity and repository design
- Session-based authentication and role-based access control
- Input validation, CORS, profile, and file-upload workflows
- Third-party email API integration and secret isolation
- HTML/CSS/JavaScript front-end integration
- Git pull-request collaboration and iterative delivery

## Academic and confidentiality note

The original implementation remains in a private university team repository. This portfolio contains no course source code, real credentials, local database files, or teammate-owned implementation. See [NOTICE.md](NOTICE.md).

