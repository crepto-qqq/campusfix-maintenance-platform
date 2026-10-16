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

### One-time local configuration

Each team member runs a separate local copy of CampusFix. The same shared Brevo
API key works on Windows, macOS, and Linux; it is not tied to a computer or an
operating system.

From the repository root, create an ignored local configuration file.

Windows PowerShell:

```powershell
Copy-Item .env.example backend/.env.local
```

macOS, Linux, or Git Bash:

```bash
cp .env.example backend/.env.local
```

Edit `backend/.env.local` and replace every example value:

```properties
CAMPUSFIX_ADMIN_PASSWORD=your-strong-local-admin-password
CAMPUSFIX_BREVO_API_KEY=xkeysib-your-shared-private-key
CAMPUSFIX_EMAIL_FROM_NAME=CampusFix
CAMPUSFIX_EMAIL_FROM_ADDRESS=your-verified-sender@example.com
CAMPUSFIX_GOOGLE_MAPS_API_KEY=your-browser-restricted-google-maps-key
CAMPUSFIX_EMAIL_VERIFICATION_REQUIRED=true
```

`backend/.env.local` is loaded automatically and ignored by Git. Never commit,
email, screenshot, or paste its API key into the front-end. Share the key only
through the team's agreed private channel. The repository contains only
`.env.example`, which has placeholder values.

The optional interactive setup wizard opens the relevant Brevo pages and writes
the local file without displaying the API key.

Windows PowerShell (Git Bash is not required):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-brevo-email.ps1
```

macOS or Linux:

```bash
bash scripts/setup-brevo-email.sh
```

### Start the back-end

```bash
cd backend
./gradlew bootRun
```

On Windows, use `.\gradlew.bat bootRun` for the back-end command. The back-end
runs at `http://localhost:8080`.

### Enable real registration email verification

Registration uses Brevo's Transactional Email REST API. It calls
`POST https://api.brevo.com/v3/smtp/email` from the back-end with the private
API key in the `api-key` request header. The API key is never sent to the
browser.

The Brevo account owner performs this one-time shared setup:

1. Create or sign in to the team's Brevo account.
2. Open **Settings → Senders & IP → Senders**, add the dedicated CampusFix
   mailbox, and enter the verification code delivered to that mailbox.
3. Open **Settings → SMTP & API → API Keys & MCP**, generate an API key named
   `CampusFix shared demo`, and copy it when it is shown. Brevo shows it only
   once.
4. Set an expiry that covers the assessment period, or manually deactivate the
   key no later than **7 November 2026**.
5. Privately give each member the verified sender address and shared key. Each
   member stores them only in their own `backend/.env.local`.

Brevo setup references: [create and verify a sender](https://help.brevo.com/hc/en-us/articles/208836149-Create-a-new-sender-From-name-and-From-email),
[create and manage API keys](https://help.brevo.com/hc/en-us/articles/209467485-Create-and-manage-your-API-keys), and
[send a transactional email through the API](https://developers.brevo.com/reference/sendtransacemail).

For team-member setup, IP allowlisting, troubleshooting, and key rotation, see
the [CampusFix Brevo Email Setup Guide](docs/Brevo_Email_Setup_Guide.md).

The registration workflow is:

1. Enter a real email address and select **Send code**.
2. Enter the six-digit code received by email within 10 minutes.
3. Submit the registration form. Public registration always creates a Student
   identity with Requester access.

The API enforces a 60-second resend cooldown and locks a code after five
incorrect attempts. Verification codes are stored only as salted BCrypt hashes
and are removed after successful registration.

### Open the front-end

From the repository root, serve the front-end over HTTP so browser sessions and
API calls behave consistently.

Windows:

```powershell
py -m http.server 5500 --directory frontend
```

macOS or Linux:

```bash
python3 -m http.server 5500 --directory frontend
```

Open `http://localhost:5500/index.html`. Every team member starts both commands
on their own computer. Separate browsers or private windows can be used to
demonstrate different signed-in users on the same computer.

### Enable Google Maps location selection

The request form continues to support manual locations when Google Maps is not
configured. To enable place search and map pin selection:

1. Create or select a Google Cloud project and attach a billing account.
2. Enable **Maps JavaScript API** and **Places API (New)**.
3. Create a browser API key and restrict it to these HTTP referrers for local
   development:
   `http://localhost:5500/*` and `http://127.0.0.1:5500/*`.
4. Restrict the key to **Maps JavaScript API** and **Places API (New)**.
5. Add the key to the ignored `backend/.env.local` file:

```properties
CAMPUSFIX_GOOGLE_MAPS_API_KEY=your-browser-restricted-google-maps-key
```

Restart the back-end after changing `.env.local`. Browser API keys are visible
to site visitors by design, so referrer restrictions, API restrictions, usage
quotas, and billing alerts must be configured in Google Cloud.

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
