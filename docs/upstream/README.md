# CampusFix

**ELEC5619 - Lab03 - Group 03**

*Campus Maintenance Request and Tracking System*

## Project Overview

CampusFix is a full-stack web application designed to improve the process of reporting, managing and tracking maintenance issues within a university campus.

The platform allows students and university staff to submit maintenance requests and monitor their progress. Administrators can review, prioritise and assign requests to appropriate maintenance staff. Maintenance staff can view their assigned tasks, update repair progress and record the work completed.

CampusFix aims to provide a centralised, transparent and efficient maintenance management platform for the university community.

## Problem Statement

University students and staff may encounter damaged facilities, electrical faults, plumbing problems, network issues, air-conditioning failures and other maintenance problems on campus.

Existing reporting methods can be fragmented and inefficient. Users may not know whether their issue has been received, assigned or resolved. They may also need to contact different departments to obtain progress updates.

Maintenance teams may find it difficult to prioritise requests, assign appropriate workers and communicate progress clearly. A lack of centralised records can also make it difficult for administrators to evaluate recurring problems and maintenance performance.

CampusFix addresses these problems by providing a single platform for submitting, assigning, processing and tracking campus maintenance requests.

## Project Objectives

The main objectives of CampusFix are to:

- Centralise campus maintenance requests in one system.
- Allow users to submit requests and track repair progress.
- Help administrators review, prioritise and assign requests.
- Allow maintenance staff to manage their assigned tasks.
- Improve communication between users and maintenance teams.
- Maintain clear records of maintenance activities.
- Collect ratings and feedback after repairs are completed.
- Provide administrators with useful maintenance statistics.

## Target Users

### Students and University Staff

Students and staff can:

- Register and log in to the system.
- Submit maintenance requests.
- Provide the location and description of an issue.
- Select an issue category and urgency level.
- Upload images of damaged facilities.
- Track the status of submitted requests.
- Add comments when further information is required.
- Rate completed maintenance services.

### Maintenance Staff

Maintenance staff can:

- View maintenance requests assigned to them.
- Review issue details, locations and attached images.
- Update the progress of assigned tasks.
- Add repair notes and completion information.
- Mark maintenance work as completed.

### System Administrators

System administrators can:

- View and manage all maintenance requests.
- Search and filter requests.
- Review request categories and urgency levels.
- Set or update ticket priorities.
- Assign requests to appropriate maintenance staff.
- Monitor the progress of maintenance work.
- View maintenance statistics and user feedback.
- Manage user roles and permissions.

## Key Functional Requirements

### User Authentication and Authorisation

- Users must be able to register and log in securely.
- Users must be able to log out of the system.
- The system must support role-based access control.
- Students, maintenance staff and administrators must have different permissions.
- Users must only be able to access functions permitted for their roles.

### Maintenance Request Management

- Students and university staff must be able to create maintenance requests.
- Each request must include a title, description, category, location and urgency level.
- Users should be able to attach an image to a request.
- Users must be able to view their submitted requests.
- Users must be able to track the current status of each request.
- Administrators must be able to view and manage all requests.

### Ticket Status Tracking

Each maintenance request should follow a clear workflow:

1. Submitted
2. Assigned
3. In Progress
4. Completed
5. Closed

Administrators and maintenance staff must be able to update request status according to their permissions. Users must be able to view status changes.

### Priority and Category Management

Maintenance requests may be classified into categories such as:

- Electrical
- Plumbing
- Network
- Air Conditioning
- Furniture
- Building Facilities
- Cleaning
- Other

Requests may also be assigned one of the following priority levels:

- Low
- Medium
- High
- Urgent

The system should help administrators identify and process urgent requests first.

### Maintenance Staff Assignment

- Administrators must be able to assign requests to maintenance staff.
- Maintenance staff must be able to view tasks assigned to them.
- Assignment information must be recorded in the system.
- Administrators should be able to reassign a request when necessary.

### Search and Filtering

Users with appropriate permissions should be able to search or filter maintenance requests by:

- Request status
- Category
- Location
- Priority
- Assigned maintenance staff
- Submission date

### Comments and Repair Records

- Users and maintenance staff should be able to add relevant comments.
- Maintenance staff should be able to record repair details.
- The system should display the history of important request updates.
- Completed requests should include completion notes.

### Rating and Feedback

- Users should be able to rate a completed maintenance service.
- Ratings should use a one-to-five-star scale.
- Users should be able to provide written feedback.
- Administrators should be able to review service ratings and feedback.

### Administrative Dashboard

The administrative dashboard may display:

- Total number of requests
- Number of open and completed requests
- Requests grouped by category
- Requests grouped by priority
- Average request completion time
- Average service rating

## Non-Functional Requirements

### Usability

- The website should have a clear and consistent interface.
- Important functions should be easy to locate.
- The application should be usable on common desktop and mobile screen sizes.
- Status, priority and category information should be clearly presented.

### Security

- User passwords must be stored securely.
- Access to protected functions must require authentication.
- Users must not be able to access functions outside their assigned roles.
- User input must be validated before being processed.
- Sensitive information must not be exposed to unauthorised users.

### Reliability

- Maintenance requests must be stored persistently.
- The system should prevent accidental loss of request data.
- Errors should be handled and communicated clearly to users.

### Performance

- Common pages and requests should respond within a reasonable time.
- Search and filtering should remain responsive as the number of maintenance requests increases.

### Maintainability

- The project should use a clear and modular structure.
- Front-end and back-end components should have clearly defined responsibilities.
- Code should follow consistent naming and formatting conventions.
- Important functions should be documented and tested.

## Proposed Technology Stack

| Component | Proposed Technology |
|---|---|
| Front-end | React |
| Back-end | Spring Boot |
| Database | MySQL or PostgreSQL |
| API Architecture | REST API |
| Authentication | Spring Security and JWT |
| Version Control | Git and GitHub |
| Project Management | JIRA |
| Testing | JUnit and front-end testing tools |

The final technology choices may be adjusted after team discussion and tutor feedback.

## Initial System Entities

The proposed system may include the following entities:

| Entity | Purpose |
|---|---|
| User | Stores user account details and roles |
| Maintenance Request | Stores issue details, location, priority and status |
| Category | Stores maintenance issue categories |
| Assignment | Records the maintenance staff assigned to a request |
| Attachment | Stores images associated with requests |
| Comment | Stores communication and progress notes |
| Rating | Stores ratings and feedback for completed requests |

## Project Scope

### Minimum Viable Product

The initial version of CampusFix will focus on:

- User registration and login
- Role-based access control
- Maintenance request submission
- Request viewing and status tracking
- Administrator request management
- Maintenance staff assignment
- Repair progress updates
- Search and filtering
- Persistent database storage

### Optional Features

The following features may be implemented if sufficient time is available:

- Image attachments
- Email or in-system notifications
- User ratings and feedback
- Administrative data visualisations
- Automatic priority recommendations
- Duplicate request detection
- Repair completion photographs

### Out of Scope

The following features are not currently included in the planned project scope:

- Integration with the university's real account system
- Integration with real university maintenance departments
- GPS navigation
- A native mobile application
- AI-based image recognition
- Real-time video or voice communication

## Git Workflow

- The `main` branch will contain the stable project version.
- Each team member will create a personal development branch.
- Team members will complete their assigned work in their own branches.
- Completed work will be reviewed before being merged into `main`.
- Commit messages will follow the required Conventional Commits format.

Examples:

- `feat: Add maintenance request form`
- `fix: Correct ticket status update`
- `docs: Update project requirements`
- `test: Add maintenance service tests`
- `chore: Configure database connection`

## Team Members and Responsibilities

| Team Member | Primary Role | Responsibilities |
|---|---|---|
| To be confirmed | Team Leader | Project coordination, JIRA management and tutor communication |
| To be confirmed | Front-end Developer | User interface design and React components |
| To be confirmed | Back-end Developer | Spring Boot APIs and business logic |
| To be confirmed | Database Developer | Database design and data integration |
| To be confirmed | QA and Documentation | Testing, documentation and submission preparation |

Roles represent each member's primary responsibilities. All team members are expected to contribute to project planning, implementation, testing and documentation.

## Current Project Status

The project topic has been selected, and the team is completing Sprint 0 activities, including:

- Confirming the project scope
- Setting up the GitHub repository
- Creating personal development branches
- Preparing the project README
- Identifying initial requirements and use cases
- Creating and assigning Sprint 0 tasks in JIRA
```
