# FR05–FR08 实现说明（中文版）

> **模块 2 – 维修请求管理**  
> 本文档记录功能需求 FR05–FR08（以及它们所依赖的 FR04 角色访问控制基础）的实现内容、文件改动、API 接口、测试步骤和已知问题。  
> **所属分支：** `weiyiwang`

---

## 1. 概览

本分支实现了 CampusFix 系统的维修请求（工单）管理功能：

| 需求 | 说明 | 状态 |
|---|---|---|
| FR04 | 角色权限控制（基础） | 已实现 |
| FR05 | 提交维修请求（标题、描述、类别、位置、紧急程度） | 已实现 |
| FR06 | 上传问题图片（每个请求可上传一张或多张） | 已实现 |
| FR07 | 在维修开始前编辑或取消维修请求 | 已实现 |
| FR08 | 查看当前用户的维修请求历史 | 已实现 |

所有功能都基于现有的会话认证（HTTP Session + `JSESSIONID` Cookie）。用户只能查看和管理自己提交的请求。

---

## 2. 文件改动

### 后端新增文件

| 文件 | 用途 |
|---|---|
| `backend/src/main/java/com/campusfix/ticket/Ticket.java` | 维修请求实体 |
| `backend/src/main/java/com/campusfix/ticket/TicketImage.java` | 问题图片实体 |
| `backend/src/main/java/com/campusfix/ticket/TicketRepository.java` | 工单仓库 |
| `backend/src/main/java/com/campusfix/ticket/TicketImageRepository.java` | 图片仓库 |
| `backend/src/main/java/com/campusfix/ticket/TicketService.java` | 工单与图片业务逻辑 |
| `backend/src/main/java/com/campusfix/ticket/TicketController.java` | 工单与图片 REST 接口 |
| `backend/src/main/java/com/campusfix/ticket/TicketRequest.java` | 创建/编辑请求的 DTO |
| `backend/src/main/java/com/campusfix/ticket/TicketCategory.java` | 枚举：维修类别 |
| `backend/src/main/java/com/campusfix/ticket/TicketStatus.java` | 枚举：请求状态 |
| `backend/src/main/java/com/campusfix/ticket/UrgencyLevel.java` | 枚举：紧急程度 |
| `backend/src/main/java/com/campusfix/user/Role.java` | 枚举：用户角色（FR04） |
| `backend/src/main/java/com/campusfix/user/RoleUpdateRequest.java` | 角色更新请求 DTO（FR04） |
| `backend/src/main/java/com/campusfix/config/DataInitializer.java` | 首次运行时创建默认管理员账号 |

### 后端修改文件

| 文件 | 改动 |
|---|---|
| `backend/src/main/java/com/campusfix/user/User.java` | 新增 `role` 字段（默认 `GENERAL_USER`） |
| `backend/src/main/java/com/campusfix/user/UserService.java` | 新增 `updateRole` 方法 |
| `backend/src/main/java/com/campusfix/user/UserController.java` | 新增 `PUT /{userId}/role`；个人资料响应现在包含 `role` |
| `backend/src/main/java/com/campusfix/auth/AuthController.java` | 登录/注册响应现在包含 `role` |

### 前端修改文件

| 文件 | 改动 |
|---|---|
| `frontend/workspace.html` | 重建为完整的维修请求工作台（提交表单 + 请求列表） |
| `frontend/js/app.js` | 新增工单提交、编辑、取消、历史记录、图片上传/展示、角色徽章逻辑 |
| `frontend/profile.html` | 新增角色显示 |
| `frontend/css/styles.css` | 新增工作台、工单卡片、状态徽章、紧急程度颜色、角色徽章样式 |

---

## 3. 枚举定义

### TicketStatus（请求状态）

`SUBMITTED`（已提交）、`ASSIGNED`（已分配）、`IN_PROGRESS`（处理中）、`COMPLETED`（已完成）、`CLOSED`（已关闭）、`REOPENED`（已重新打开）、`CANCELLED`（已取消）

### TicketCategory（维修类别）

`ELECTRICAL`（电气）、`PLUMBING`（水暖）、`HVAC`（暖通空调）、`CARPENTRY`（木工）、`CLEANING`（清洁）、`IT`（信息技术）、`GROUNDS`（场地）、`OTHER`（其他）

### UrgencyLevel（紧急程度）

`LOW`（低）、`MEDIUM`（中）、`HIGH`（高）、`EMERGENCY`（紧急）

### Role（角色，FR04）

`GENERAL_USER`（普通用户）、`MAINTENANCE_STAFF`（维修人员）、`ADMINISTRATOR`（管理员）

---

## 4. API 接口

基础地址：`http://localhost:8080/api`

### 工单（FR05、FR07、FR08）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/tickets` | 创建维修请求 |
| GET | `/tickets` | 列出当前用户的请求（最新优先） |
| GET | `/tickets/{ticketId}` | 获取当前用户拥有的单条请求 |
| PUT | `/tickets/{ticketId}` | 编辑请求（仅 `SUBMITTED` 状态） |
| POST | `/tickets/{ticketId}/cancel` | 取消请求（仅 `SUBMITTED` 状态） |

### 图片（FR06）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/tickets/{ticketId}/images` | 上传一张图片（`multipart/form-data`，字段名为 `image`） |
| GET | `/tickets/{ticketId}/images/{imageId}` | 查看图片 |
| DELETE | `/tickets/{ticketId}/images/{imageId}` | 删除图片（仅 `SUBMITTED` 状态） |

### 角色管理（FR04）

| 方法 | 路径 | 用途 | 权限 |
|---|---|---|---|
| PUT | `/users/{userId}/role` | 修改用户角色 | 仅管理员 |

---

## 5. 请求 / 响应格式

### 创建 / 编辑请求体

```json
{
  "title": "空调漏水",
  "description": "三楼空调一直滴水。",
  "category": "HVAC",
  "location": "图书馆三楼",
  "urgency": "HIGH"
}
```

### 工单响应

```json
{
  "id": 1,
  "title": "空调漏水",
  "description": "三楼空调一直滴水。",
  "category": "HVAC",
  "location": "图书馆三楼",
  "urgency": "HIGH",
  "status": "SUBMITTED",
  "createdAt": "2026-08-22T15:00:00",
  "updatedAt": "2026-08-22T15:00:00",
  "imageIds": [1, 2]
}
```

### 修改角色请求体（FR04）

```json
{
  "role": "MAINTENANCE_STAFF"
}
```

---

## 6. 业务规则

- 新请求创建后状态为 `SUBMITTED`。
- 用户只能查看和修改自己的请求；访问他人请求返回 `404` 或 `403`。
- 编辑、取消、添加图片和删除图片**仅在状态为 `SUBMITTED` 时允许**（即维修工作开始前）。其他状态返回 `409 CONFLICT`。
- 图片限制：
  - 每个请求最多 5 张图片。
  - 每张图片最大 15 MB。
  - 只接受 `image/*` 内容类型。
- 角色变更只能由 `ADMINISTRATOR` 执行，否则返回 `403 FORBIDDEN`。

---

## 7. 测试步骤

### 7.1 环境准备

1. 启动后端：
   ```powershell
   cd backend
   .\gradlew.bat bootRun
   ```
   等待日志出现 `Started BackendApplication` / `Tomcat started on port 8080`。
2. 使用 **VS Code Live Server**（端口 5500）打开前端：
   - 右键 `frontend/index.html` → **Open with Live Server**。
   - 地址栏必须显示 `http://localhost:5500/index.html`（**不要**通过 `file://` 打开）。
3. 整个测试过程固定使用一个主机名（`http://localhost:5500`，不要用 `127.0.0.1`）。

默认管理员账号（数据库为空时首次启动自动创建）：

| 邮箱 | 密码 |
|---|---|
| admin@campusfix.edu | admin12345 |

> 如果数据库是由旧版本应用创建的，删除 `backend/data` 后重启，以便创建新的 `role` 列。

### 7.2 登录与角色显示（FR01/FR02/FR04 回归）

| # | 步骤 | 预期结果 |
|---|---|---|
| 1 | 注册新用户（`usera@test.com` / `password123`） | `Registration successful. User ID: X` |
| 2 | 用相同邮箱再次注册 | `This email is already registered.` |
| 3 | 以用户 A 登录 | 跳转到工作台并稳定停留（不会弹回） |
| 4 | 查看工作台右上角 | 显示蓝色 `General User` 徽章 |
| 5 | 打开个人资料页 | 显示 `Role: General User` |
| 6 | 退出登录 | 返回登录页 |
| 7 | 用 `admin@campusfix.edu` / `admin12345` 登录 | 显示红色 `Administrator` 徽章 |

### 7.3 提交请求（FR05）

| # | 步骤 | 预期结果 |
|---|---|---|
| 1 | 标题留空提交 | 浏览器阻止提交（必填项） |
| 2 | 提交完整请求（标题/描述/类别/位置/紧急程度） | `Request submitted`，卡片出现黄色 `Pending` 徽章 |
| 3 | 只填必填项提交（不选图片） | 提交成功，无图片 |
| 4 | 提交多条请求 | 列表按创建时间倒序排列（最新在上） |

### 7.4 上传图片（FR06）

| # | 步骤 | 预期结果 |
|---|---|---|
| 1 | 提交时选 1 张图片 | 卡片上显示缩略图 |
| 2 | 提交时选 3 张图片 | 3 张缩略图都显示 |
| 3 | 提交时选 5 张图片 | 5 张缩略图，无报错 |
| 4 | 上传第 6 张图片（通过 API） | `A request cannot have more than 5 images` |
| 5 | 上传非图片文件（通过 API） | `File must be an image` |
| 6 | 上传超过 15 MB 的图片（通过 API） | `Image must not exceed 15 MB` |

### 7.5 编辑 / 取消（FR07）

| # | 步骤 | 预期结果 |
|---|---|---|
| 1 | 点击 `Pending` 请求的 `Edit` | 表单回填；标题变为 `Edit Maintenance Request`；按钮变为 `Save Changes` |
| 2 | 修改标题后保存 | `Request updated`；列表更新 |
| 3 | 点击 `Cancel Edit` | 表单重置为新建模式 |
| 4 | 点击 `Cancel Request` 并确认 | 状态变为灰色 `Cancelled`；编辑/取消按钮消失 |
| 5 | 再次取消同一请求（通过 API） | `409` |

### 7.6 查看历史（FR08）

| # | 步骤 | 预期结果 |
|---|---|---|
| 1 | 以用户 A 刷新工作台 | 显示用户 A 的所有请求，最新优先 |
| 2 | 退出并以用户 B 登录 | 看不到用户 A 的请求 |
| 3 | 以用户 B 通过 API 访问用户 A 的请求 | `404` 或 `403` |

### 7.7 角色管理（FR04）

1. 以管理员登录并保存会话（Postman/curl）。
2. 调用：
   ```
   PUT http://localhost:8080/api/users/{userId}/role
   {"role": "MAINTENANCE_STAFF"}
   ```

| # | 步骤 | 预期结果 |
|---|---|---|
| 1 | 管理员修改角色 | `200`，响应 `role` 为 `MAINTENANCE_STAFF` |
| 2 | 被修改用户重新登录 | 显示紫色 `Maintenance Staff` 徽章 |
| 3 | 非管理员调用角色接口 | `403 Administrator access required` |
| 4 | 未登录调用角色接口 | `401 Login is required` |

### 7.8 安全 / 边界情况

| # | 场景 | 预期结果 |
|---|---|---|
| 1 | 未登录打开 `workspace.html` | 跳转到登录页 |
| 2 | 未登录打开 `profile.html` | 跳转到登录页 |
| 3 | 编辑非 `SUBMITTED` 状态的请求（通过 API） | `409` |
| 4 | 在标题中注入 `<script>alert(1)</script>` | 以纯文本显示，不弹窗（XSS 已转义） |

---

## 8. 已知问题 / 注意事项

1. **密码以明文存储**（沿用原有 `UserService` 的遗留行为）。FR02 的"安全登录"理想情况下应使用 BCrypt 等密码哈希。本次有意保持不变，以免影响已有账号。
2. **CORS / 主机名耦合**：前端根据当前页面主机名构建 API 地址（`http://${location.hostname}:8080/api`），以便正确发送会话 Cookie。请始终使用 `http://localhost:5500`（Live Server），并避免混用 `localhost` 与 `127.0.0.1`。
3. **不支持 `file://`**：双击打开前端 HTML 会破坏 CORS 和会话 Cookie，请使用 Live Server。
4. **旧数据库兼容性**：如果 H2 数据库文件是在 `role` 列存在之前创建的，请删除 `backend/data` 后重启后端。
5. **无自助注册维修人员/管理员**：所有自助注册的账号均为 `GENERAL_USER`。维修人员和管理员角色需由管理员通过角色接口分配。

---

## 9. 建议的提交信息

```
feat: implement maintenance request management (FR05-FR08)

- Add ticket entity, repository, service, and REST controller
- Add issue image upload/view/delete with size/count validation
- Add edit and cancel (only while SUBMITTED)
- Add request history listing for the current user
- Add role field and admin-only role update endpoint (FR04)
- Rebuild workspace UI for request submission and tracking
- Add default administrator initializer
- Update profile to display user role
