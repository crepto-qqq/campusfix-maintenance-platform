# F03 Implementation Summary

## 多角色更新

当前分支已将单角色管理升级为多角色集合。管理员页面使用复选框；后端按所有有效角色授权，保留旧单角色接口的替换语义。具体接口、权限边界和验收步骤见 [F03 多角色访问控制](F03-RBAC-CN.md)。

下面记录早期单角色版本的实现背景；当前行为以链接文档为准。

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
