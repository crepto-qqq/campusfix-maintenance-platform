# Architecture

## System view

```mermaid
flowchart LR
    U[Requester / Staff / Administrator] --> F[HTML, CSS, JavaScript client]
    F -->|REST and session cookie| C[Spring Boot controllers]
    C --> V[Validation and access checks]
    V --> S[Application services]
    S --> R[JPA repositories]
    R --> D[(H2 relational database)]
    S --> N[In-app notifications]
    S --> E[Email verification gateway]
    E --> B[Brevo transactional-email API]
```

## Layer responsibilities

| Layer | Responsibility |
|---|---|
| Browser | Forms, client-side interaction, role-aware navigation, profile UI |
| Controller | REST contract, request parsing, session boundary, HTTP responses |
| Service | Registration, authentication, profile and workflow business rules |
| Repository | JPA/Hibernate persistence and domain lookup |
| Database | Users, identities, role assignments, tickets, history, evidence and notifications |
| Email gateway | Server-side verification-email delivery without exposing the API key to the browser |

## Identity and access slice

```mermaid
sequenceDiagram
    participant Browser
    participant AuthController
    participant VerificationService
    participant UserService
    participant Repository
    participant Brevo

    Browser->>AuthController: Request verification code
    AuthController->>VerificationService: Create time-limited challenge
    VerificationService->>Brevo: Send transactional email
    Brevo-->>Browser: Verification code by email
    Browser->>AuthController: Register with code and profile data
    AuthController->>VerificationService: Validate challenge
    AuthController->>UserService: Create validated account
    UserService->>Repository: Persist user, identity and role assignment
    Repository-->>UserService: Created account
    UserService-->>Browser: Authenticated response / session
```

Secrets are supplied only to the back end through an ignored local environment file. The public portfolio includes neither the key nor the implementation source.

