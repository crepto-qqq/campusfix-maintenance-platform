# F03 Implementation Summary

## Completed Work / 已完成工作

- Added `STUDENT` and `CAMPUS_STAFF` identity types.
- Public registration现在固定创建 **Student + Requester**，不能自行注册为Staff或Admin。
- Added role-aware workspace for `GENERAL_USER`, `MAINTENANCE_STAFF`, `ADMINISTRATOR`, and `MANAGER`.
- Admin登录后先进入初始Workspace，并通过 **Manage Users** 进入Users and Roles页面。
- Added Admin user management API and UI，管理员可以同时修改用户的 `identityType` 和 `role`。
- Identity和Role修改会同步到Backend及H2 Database，用户重新登录后会获得最新权限。
- Added server-side access control，非Admin访问用户管理接口会返回 `403 Forbidden`。
- Prevented demotion of the last Administrator.

## Fixes and Improvements / 修复与优化

- Fixed the issue where **Manage Users** was incorrectly visible to Student users.
- Fixed Admin login redirecting directly to Users and Roles instead of the initial Workspace.
- Fixed `User access update failed (500)` caused by unnamed `@PathVariable` parameters.
- Added explicit names for all `userId`, `ticketId`, and `imageId` path variables.
- Enabled Java `-parameters` compilation to keep parameter metadata consistent between VS Code and Gradle.
- Improved role-based page visibility and added Maintenance Staff/Manager workspace placeholders.
- Added F03 API and permission documentation.

## Verification / 验证结果

- Java 21 Gradle build passed.
- Front-end JavaScript syntax check passed.
- Student registration is forced to `STUDENT + GENERAL_USER`.
- Requester access to Admin APIs correctly returns `403`.
- Admin can update Identity and Role, and the changes persist in the database.
- The updated Identity and Role are returned after the user logs in again.

