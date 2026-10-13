# F13 — 工单重新分配

## 行为

仅以 `ADMINISTRATOR` 为当前活动角色的登录用户可以把已有有效分配的工单转给另一名有效维修人员。目标账号必须处于 `ACTIVE` 状态并拥有 `MAINTENANCE_STAFF` 角色。

允许重新分配的状态为：

- `ASSIGNED`
- `IN_PROGRESS`
- `REOPENED`

重新分配后工单统一回到 `ASSIGNED`，让新维修人员明确开始处理。`SUBMITTED` 应使用 F12 首次分配；`COMPLETED`、`CLOSED` 和 `CANCELLED` 不能直接重新分配。

## API

```http
POST /api/tickets/{ticketId}/reassignment
Content-Type: application/json

{
  "technicianId": 12,
  "reason": "Current technician is unavailable"
}
```

`technicianId` 必须是正数，`reason` 必填、去除首尾空格后不能为空，最长 1000 个字符。选择当前维修人员、无效或停用账号、非维修人员都会被拒绝。

## 一致性与审计

重新分配在一个数据库事务中完成：

1. 锁定目标工单，避免并发操作产生多个有效分配。
2. 结束旧 `ticket_assignments` 记录，写入 `ended_at`、`is_active=false` 和原因。
3. 新建有效分配记录，并将工单状态设为 `ASSIGNED`。
4. 写入 `TICKET_REASSIGNED` 历史，保存旧/新状态、旧/新维修人员、管理员、原因和时间。
5. 给原维修人员、新维修人员和报修者创建站内通知；如果身份重叠，按用户去重。

旧维修人员在事务完成后立即失去该工单的查看与状态更新权限，新维修人员立即获得权限。

## 前端

管理员在 `ASSIGNED`、`IN_PROGRESS` 或 `REOPENED` 工单卡片中看到 **Reassign Technician**。下拉列表不会显示当前维修人员；必须选择另一名维修人员并填写原因才能提交。

成功后页面重新加载工单数据，显示新的维修人员和 `ASSIGNED` 状态。失败时页面显示后端返回的原因。

## 验证

在 `backend` 目录运行：

```bash
bash ./gradlew test
```

在项目根目录运行：

```bash
node --test frontend/tests/multiple-roles.test.cjs
```

自动化测试覆盖身份和角色限制、请求验证、无效目标、状态限制、状态重置、旧/新分配记录、审计历史、通知、旧维修人员撤权、新维修人员授权，以及重复操作不产生额外副作用。
