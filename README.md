# CampusFix Campus Maintenance Workflow Platform

> CampusFix 校园维修工单与协同管理平台

CampusFix is a completed university team project connecting requesters, maintenance staff and administrators in one full-stack maintenance workflow. This public repository now includes application source and filtered development history, not only a project introduction.

Refreshed on **2 November 2026** from the school's committed `main` at [`53e0134`](https://github.sydney.edu.au/2026S2-Object-Oriented-App-Frameworks/ELEC5619-Lab03-Group-03/commit/53e0134e1d416d513db6b89954b0c84e872b2d9f). The source link requires university access. Local uncommitted work is not included. See [source provenance](SOURCE_ORIGIN.md).

## Application features

- Email-verified registration, login/logout, profiles, avatars and multiple account roles.
- Maintenance requests with categories, location, optional maps and image attachments.
- Administrator priority setting, assignment, reassignment and maintenance analytics.
- Technician workspaces, status transitions, completion evidence and audit history.
- Ticket conversations, role-aware notifications, service ratings and review moderation.

The [requirements and closeout guide](docs/Requirements-Closeout-CN.md) maps FR01–FR20 to the implementation. Completion of the course project does not imply production security certification or verified real-email, maps, load and cross-device performance.

## Technology and structure

| Area | Implementation |
|---|---|
| Front end | HTML, CSS, JavaScript and browser Fetch APIs |
| Back end | Java 21, Spring Boot and REST endpoints |
| Persistence | JPA/Hibernate and local H2 storage |
| External services | Brevo transactional email; optional Google Maps |
| Verification | JUnit/Spring integration tests and Node's test runner |
| Collaboration | Git pull requests, iterative delivery and Jira/Scrum |

Application code is in `backend/src/` and `frontend/`; tests are in `backend/src/test/` and `frontend/tests/`. Setup scripts are in `scripts/`. See the [architecture notes](docs/ARCHITECTURE.md).

## Run locally

Install a Java 21 JDK and Python 3. Node.js is needed for frontend tests, not for serving the static pages. The Gradle wrapper downloads its pinned build distribution and dependencies.

1. Copy `.env.example` to `backend/.env.local` and replace placeholders with your own local administrator password and service credentials. Never commit that file.
2. Configure a verified Brevo sender using the [email setup guide](docs/Brevo_Email_Setup_Guide.md). Keep email verification enabled for normal use.
3. From `backend/`, run `./gradlew bootRun` on macOS/Linux or `.\gradlew.bat bootRun` on Windows. The API runs at `http://localhost:8080`.
4. From the repository root, run `python -m http.server 5500 --bind 127.0.0.1 --directory frontend`, then open `http://localhost:5500`.

The application creates a fresh local database. No original users, tickets or database files are published. Decorative background images/video are omitted because their redistribution provenance was not established; the CSS fallback remains. External email and maps need separately configured credentials.

## Verification

Verified on 2 November 2026 against this filtered source: **32 backend tests and 39 frontend tests passed**, with no failures or skips. JavaScript syntax checks also passed.

From `backend/`, run `.\gradlew.bat test` on Windows or `./gradlew test` on macOS/Linux. From the repository root:

```text
node --test frontend/tests/closeout.test.cjs frontend/tests/multiple-roles.test.cjs frontend/tests/reviews.test.cjs
```

Tests use synthetic accounts and isolated databases. They do not validate delivery to a real mailbox, live maps or production security.

## My contribution

I implemented identity/access and profile flows, Brevo integration, role-aware workspaces, ratings/moderation workflows and integration fixes. The wider maintenance system was developed with teammates; I do not claim every component.

The latest filtered import preserves **92 source-relevant commits**, including **71 attributed to Tian Liao**. Counts include merges and are not a contribution percentage. See [contribution evidence](docs/CONTRIBUTIONS.md).

## Publication boundary

Team permission covers the retained team-authored source. Real credentials, databases, user records, build caches, private course deliverables and unverified decorative media are excluded from the imported history. Private author emails are replaced while names, dates and development order remain.

The Gradle wrapper is third-party tooling, not my work. Its licence is retained inside the JAR; see [third-party notices](THIRD_PARTY_NOTICES.md). Team code is not assigned a new open-source licence by this publication; see [NOTICE.md](NOTICE.md).
