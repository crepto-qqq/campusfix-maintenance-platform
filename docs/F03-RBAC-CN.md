# F03 — 基于角色的访问控制

## 目标

F03让系统根据登录用户在数据库中保存的角色决定其可用入口和后端权限。用户登录时不选择角色，服务器从账号记录中读取角色。

## 身份和角色

公开注册固定创建 `STUDENT` 身份和 `GENERAL_USER` 角色，前端显示名称为 Requester。注册请求不能指定Campus Staff身份、管理员或维修人员权限。

管理员可以分配以下角色：

- `GENERAL_USER`：Requester，使用个人维修请求工作区。
- `MAINTENANCE_STAFF`：Maintenance Staff，后续接入分派工单工作区。
- `ADMINISTRATOR`：Administrator，管理用户角色。
- `MANAGER`：Manager，后续接入管理报表工作区。

当前数据模型采用单角色，与现有用户表保持兼容。如果以后确认同一用户必须同时担任Requester和Technician，再通过数据库迁移升级为多角色关联表。

## 数据库字段

`app_users`增加或使用以下字段：

| Field | Type | Rule |
|---|---|---|
| `identity_type` | Enum String | 非空，现有用户默认 `STUDENT` |
| `role` | Enum String | 非空，新注册用户默认 `GENERAL_USER` |

本地开发管理员初始化为Campus Staff和Administrator。系统启动时会为旧的空身份或空角色数据补充默认值。

## API流程

### 注册

`POST /api/auth/register`

```json
{
  "name": "Example Student",
  "email": "user-3ba43c4927c4@example.invalid",
  "password": "password123"
}
```

响应包含服务器分配的 `STUDENT` 和 `GENERAL_USER`，请求体不能指定身份或角色。

### 登录和当前用户

- `POST /api/auth/login`：建立Session并返回身份和角色。
- `GET /api/users/me`：返回当前用户资料、身份和角色。
- `POST /api/auth/logout`：使Session失效。

### 管理员用户管理

- `GET /api/admin/users`：仅Administrator可以读取全部用户及角色。
- `PUT /api/admin/users/{userId}`：仅Administrator可以同时修改指定用户的大学身份和系统角色。

更新请求示例：

```json
{
  "identityType": "CAMPUS_STAFF",
  "role": "MAINTENANCE_STAFF"
}
```

非管理员返回 `403`；尝试降级最后一名Administrator返回 `409`。

## 前端流程

1. Register页面只创建Student Requester，不显示身份或角色选择。
2. 登录页面保持统一，不显示角色选择。
3. Requester登录后进入个人维修请求工作区。
4. Administrator登录后先进入Workspace，并通过Manage Users进入 `admin.html`。
5. Administrator可以在Users and Roles页面同时修改Identity和Role，保存结果同步到后端和数据库。
6. Maintenance Staff和Manager进入角色专属占位工作区，当前不会看到Requester操作。
7. 用户资料页展示University Identity和Role。

## 已验证场景

- Student注册后数据库身份为 `STUDENT`、角色为 `GENERAL_USER`。
- Requester可以登录，但访问管理员用户接口返回 `403`。
- Administrator可以读取用户列表。
- Administrator可以将Requester调整为Maintenance Staff。
- 调整后的用户重新登录时返回新角色。
- 最后一名Administrator不能被降级。
