# CampusFix

**ELEC5619 - Lab03 - Group 03**

*Campus Maintenance Request and Tracking System*

## Project Overview

CampusFix is a full-stack web application designed to improve the process of reporting, managing and tracking maintenance issues within a university campus.

Students and university staff can submit maintenance requests and monitor their progress. Administrators can review and assign requests to maintenance staff, while maintenance staff can update repair progress and completion information.

The project aims to provide a centralised, transparent and efficient maintenance management platform for the university community.

## Problem Statement

Maintenance issues such as damaged facilities, electrical faults, plumbing problems and air-conditioning failures commonly occur on campus.

Current reporting methods may be fragmented or inefficient. Users may not know whether an issue has been received, assigned or resolved, while maintenance teams may find it difficult to organise requests and communicate progress clearly.

CampusFix addresses this problem by providing one central platform for submitting, managing and tracking maintenance requests.

## Key Requirements and Main Features

The main requirements of CampusFix are:

- User registration and login
- Role-based access for users, maintenance staff and administrators
- Maintenance request submission
- Request status tracking
- Assignment of requests to maintenance staff
- Repair progress updates
- Search and filtering of maintenance requests
- Rating and feedback after maintenance is completed

A maintenance request will follow a simple workflow:

1. Submitted
2. Assigned
3. In Progress
4. Completed

## High-Level Goals

The main goals of CampusFix are to:

- Centralise campus maintenance requests in one system
- Make maintenance reporting easier for students and staff
- Allow users to clearly track repair progress
- Help administrators organise and assign maintenance work
- Improve communication between users and maintenance staff
- Collect feedback to improve maintenance service quality
- Develop a reliable full-stack web application

## Proposed Technology Stack

| Component | Technology |
|---|---|
| Front-end | HTML, CSS and JavaScript |
| Back-end | Spring Boot |
| Database | H2 (local development) |
| API | REST API |
| Version Control | Git and GitHub |
| Project Management | JIRA |

The final technology choices may be adjusted after team discussion and tutor feedback.

## Project Scope

The initial version of CampusFix will focus on the core maintenance workflow:

- Submit a maintenance request
- View and track a request
- Assign a request to maintenance staff
- Update repair progress
- Complete a maintenance request
- Provide a rating and feedback

Additional features such as image uploads, notifications and data visualisations may be considered in later sprints if time permits.

## Local Development

Requirement: Java 21.

### Start the back-end

```bash
cd backend
./gradlew bootRun
```

On Windows, use `.\gradlew.bat bootRun` for the back-end command.

Before the first start of a new local database, set a local administrator
password through the environment. Never put the real value in Git-tracked
configuration:

```powershell
$env:CAMPUSFIX_ADMIN_PASSWORD='use-a-strong-local-password'
.\gradlew.bat bootRun
```

### Open the front-end

Open `frontend/index.html` directly in a browser, or use the VS Code Live Server extension.

## Database Design

The authentication account, user profile, campus identity and role assignments are stored in normalized relational tables. See [Database ERD and user normalization](docs/Database-ERD-User-Normalization.md).

Implemented workflow documentation: [F11 priority classification](docs/F11-Priority-Classification-CN.md) and [F12 ticket assignment](docs/F12-Ticket-Assignment-CN.md).

The application resolves the repository location at startup and always uses the
repository-level `data/campusfix` H2 database. Its location does not change when
the back-end is launched from the repository, `backend`, or `frontend`
directory. The configuration intentionally has no relative-path fallback, so a
stale launch cannot silently create another database. Existing legacy user data
is migrated automatically on first startup.

## Team Members and Responsibilities

| Team Member | Primary Role | Responsibilities |
|---|---|---|
| Kairos Liu | Team Leader & Documentation | Coordinate the project, manage JIRA, communicate with the tutor, maintain documentation, and support testing |
| Tian Liao | Front-end Developer | Develop the user interface and implement maintenance request submission and tracking pages |
| Sen Wang / Weiyi Wang | Back-end Developer | Develop Spring Boot APIs, business logic and the maintenance request workflow |
| Hekang Pan | Database & Integration Developer | Design the database, manage data persistence, and support system integration and testing |

All team members will contribute to project planning, development, testing and documentation.

## Sprint 0 Activities

During Sprint 0, the team will:

- Confirm the project topic and scope
- Set up the GitHub organisation and repository
- Prepare the README document
- Create and configure the JIRA project
- Identify initial project requirements
- Identify initial use cases
- Assign Sprint 0 tasks to team members
