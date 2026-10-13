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
    TICKETS ||--o{ TICKET_ASSIGNMENTS : has
    APP_USERS ||--o{ TICKET_ASSIGNMENTS : technician
    APP_USERS ||--o{ TICKET_ASSIGNMENTS : assigns
    TICKETS ||--o{ IN_APP_NOTIFICATIONS : relates_to
    APP_USERS ||--o{ IN_APP_NOTIFICATIONS : receives
    APP_USERS ||--o{ IN_APP_NOTIFICATIONS : triggers
    TICKETS ||--o| TECHNICIAN_REVIEWS : receives
    APP_USERS ||--o{ TECHNICIAN_REVIEWS : authors
    APP_USERS ||--o{ TECHNICIAN_REVIEWS : rated_technician
    TECHNICIAN_REVIEWS ||--o{ REVIEW_REPORTS : reported_in
    APP_USERS ||--o{ REVIEW_REPORTS : reports
    TECHNICIAN_REVIEWS ||--o{ REVIEW_AUDIT : audited_in
    APP_USERS ||--o{ REVIEW_BANS : restricted_by

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
        enum urgency
        enum operational_priority
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

    TICKET_ASSIGNMENTS {
        bigint id PK
        bigint ticket_id FK
        bigint technician_id FK
        bigint assigned_by FK
        timestamp assigned_at
        timestamp ended_at
        boolean is_active
        varchar reason
    }

    IN_APP_NOTIFICATIONS {
        bigint id PK
        bigint recipient_id FK
        bigint actor_id FK
        bigint ticket_id FK "nullable for account-level notices"
        enum type
        varchar message
        varchar deduplication_key UK
        timestamp created_at
        timestamp read_at
    }

    TECHNICIAN_REVIEWS {
        bigint id PK
        bigint ticket_id FK,UK
        bigint technician_id FK
        bigint author_id FK
        int score
        varchar comment
        enum visibility
        timestamp created_at
        timestamp updated_at
    }

    REVIEW_REPORTS {
        bigint id PK
        bigint review_id FK
        bigint reporter_id FK
        varchar reason
        enum status
        varchar pending_key UK
        varchar resolution_action
        varchar resolution_reason
        bigint resolved_by FK
        timestamp created_at
        timestamp resolved_at
    }

    REVIEW_BANS {
        bigint id PK
        bigint student_id FK
        bigint administrator_id FK
        varchar reason
        timestamp expires_at
        boolean active
        timestamp created_at
        timestamp ended_at
        varchar end_reason
        bigint ended_by FK
    }

    REVIEW_AUDIT {
        bigint id PK
        bigint review_id FK
        bigint actor_id FK
        varchar action
        varchar reason
        int before_score
        int after_score
        varchar before_visibility
        varchar after_visibility
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
| `ticket_assignments` | Current and historical Technician allocation, including assigning Administrator and time |
| `in_app_notifications` | Durable, deduplicated business events and student notification centre; `ticket_id` may be absent for account-level review bans |
| `technician_reviews` | One current review per completed ticket, linked to its author and final technician; visibility supports text-only or full removal |
| `review_reports` | Student reports and their resolution, with a unique pending key per reporter and review |
| `review_bans` | Time-limited or permanent review submission bans with administrator action history |
| `review_audit` | Before/after snapshots for rating changes, moderation and restoration |

## Compatibility and Data Integrity

- `app_users.id` is preserved, so `tickets.submitted_by` and `ticket_history.actor_id` remain valid.
- Public registration still creates `STUDENT + GENERAL_USER`.
- Admin updates create/revoke `user_role_assignments`; the change persists after re-login.
- An account may have several active role assignments; each request uses one selected active role, while previous assignments remain available as audit data.
- Existing plaintext passwords are converted to BCrypt hashes during migration.
- Profile, Avatar, Request and History APIs retain their existing request and response contracts.
- `tickets.operational_priority`独立于Requester填写的`urgency`，允许`NULL`表示尚未由Administrator分级，并建立`idx_tickets_operational_priority`索引。
- 初次分派使用独立`ticket_assignments`关系表；工单状态、Assignment、History和Notification在同一事务中保存。
- 评分汇总仅包含未被完整撤下的评价；只撤下评论时评分仍然有效。公开视图不返回作者或工单的身份字段。

## Legacy Migration

At first startup after this refactor:

1. The new relational tables are created.
2. Existing profile, identity and role data are copied using the same user IDs.
3. Existing passwords are converted to BCrypt.
4. Migration row counts are checked before legacy columns are removed.
5. Required account columns are changed to `NOT NULL`.

The migration is idempotent and can safely start again after a completed migration.
