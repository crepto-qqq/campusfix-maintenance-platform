# CampusFix Database Normalization / 用户表拆分

## Overview

原有 `app_users` 同时保存 Authentication、Profile、Campus Identity、Role 和 Avatar。重构后，`app_users` 仅作为登录账户，其余职责拆分到独立关系表。现有 API 字段保持不变，因此前端无需修改。

## ERD

```mermaid
erDiagram
    APP_USERS ||--|| USER_PROFILES : has
    APP_USERS ||--|| CAMPUS_IDENTITIES : owns
    APP_USERS ||--o{ USER_ROLE_ASSIGNMENTS : receives
    SYSTEM_ROLES ||--o{ USER_ROLE_ASSIGNMENTS : defines
    APP_USERS ||--o{ USER_ROLE_ASSIGNMENTS : assigns
    APP_USERS ||--o{ CAMPUS_IDENTITIES : verifies
    APP_USERS ||--o{ TICKETS : submits
    APP_USERS ||--o{ TICKET_HISTORY : performs
    TICKETS ||--o{ TICKET_HISTORY : records
    TICKETS ||--o{ TICKET_IMAGES : contains

    APP_USERS {
        bigint id PK
        varchar email UK
        varchar password_hash
        enum account_status
        timestamp created_at
        timestamp updated_at
    }

    USER_PROFILES {
        bigint user_id PK,FK
        varchar name
        varchar contact_information
        varchar profile_details
        blob avatar_data
        varchar avatar_content_type
    }

    CAMPUS_IDENTITIES {
        bigint id PK
        bigint user_id FK,UK
        enum identity_type
        varchar university_id UK
        varchar department
        timestamp verified_at
        bigint verified_by FK
    }

    SYSTEM_ROLES {
        bigint id PK
        enum code UK
        varchar name
        varchar description
    }

    USER_ROLE_ASSIGNMENTS {
        bigint id PK
        bigint user_id FK
        bigint role_id FK
        bigint assigned_by FK
        timestamp assigned_at
        timestamp revoked_at
        boolean is_active
    }

    TICKETS {
        bigint id PK
        bigint submitted_by FK
        varchar title
        enum status
        timestamp created_at
        timestamp updated_at
    }

    TICKET_HISTORY {
        bigint id PK
        bigint ticket_id FK
        bigint actor_id FK
        enum event_type
        timestamp occurred_at
    }
```

## Responsibilities

| Table | Responsibility |
|---|---|
| `app_users` | Authentication account, account status and timestamps |
| `user_profiles` | Display name, contact details, description and avatar |
| `campus_identities` | Student/Staff identity and verification information |
| `system_roles` | Authorisation role catalogue |
| `user_role_assignments` | Current and historical role allocation, including administrator and time |

## Compatibility and Data Integrity

- `app_users.id` is preserved, so `tickets.submitted_by` and `ticket_history.actor_id` remain valid.
- Public registration still creates `STUDENT + GENERAL_USER`.
- Admin updates create/revoke `user_role_assignments`; the change persists after re-login.
- Only one active role is allowed by the service layer, while previous assignments remain available as audit data.
- Existing plaintext passwords are converted to BCrypt hashes during migration.
- Profile, Avatar, Request and History APIs retain their existing request and response contracts.

## Legacy Migration

At first startup after this refactor:

1. The new relational tables are created.
2. Existing profile, identity and role data are copied using the same user IDs.
3. Existing passwords are converted to BCrypt.
4. Migration row counts are checked before legacy columns are removed.
5. Required account columns are changed to `NOT NULL`.

The migration is idempotent and can safely start again after a completed migration.
