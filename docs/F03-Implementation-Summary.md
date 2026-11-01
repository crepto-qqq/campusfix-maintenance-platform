# F03 Implementation Summary

## 多角色更新

当前分支支持一个账号持有多个角色，管理员页面使用复选框；登录时选择一个活动角色，Dashboard 可以切换，前后端按本次活动角色授权，不叠加其他角色权限。`MANAGER` 已退役：只持有它的账号转为 `GENERAL_USER`，同时持有受支持角色的账号保留这些角色。保留旧单角色接口的替换语义。具体接口、权限边界和验收步骤见 [F03 多角色账号与活动角色](F03-RBAC-CN.md)。

下面记录早期单角色版本的实现背景；当前行为以链接文档为准。

## Completed Work / 已完成工作

- Added `STUDENT` and `CAMPUS_STAFF` identity types.
- Public registration现在固定创建 **Student + Requester**，不能自行注册为Staff或Admin。
- Added separate workspaces for `GENERAL_USER`, `MAINTENANCE_STAFF`, and `ADMINISTRATOR`.
- Admin登录后先进入初始Workspace，并通过 **Manage Users** 进入Users and Roles页面。
- Added Admin user management API and UI，管理员可以同时修改用户的 `identityType` 和 `roles`。
- Identity和Role修改会同步到Backend及H2 Database，用户的后续请求立即按新授权验证。
- Added server-side access control，非Admin访问用户管理接口会返回 `403 Forbidden`。
- Prevented demotion of the last Administrator.

## Fixes and Improvements / 修复与优化

- Fixed the issue where **Manage Users** was incorrectly visible to Student users.
- Fixed Admin login redirecting directly to Users and Roles instead of the initial Workspace.
- Fixed `User access update failed (500)` caused by unnamed `@PathVariable` parameters.
- Added explicit names for all `userId`, `ticketId`, and `imageId` path variables.
- Enabled Java `-parameters` compilation to keep parameter metadata consistent between VS Code and Gradle.
- Improved role-based page visibility and added a Maintenance Staff workspace.
- Added F03 API and permission documentation.
