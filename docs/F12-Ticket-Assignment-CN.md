# F12 — Assign Ticket to Technician / 工单初次分派

## 已实现范围

F12允许Administrator将`SUBMITTED`工单首次分配给有效且启用的`MAINTENANCE_STAFF`。成功后，Assignment、工单`ASSIGNED`状态、F15审计记录和供F17消费的Notification Event位于同一个Transaction中写入。

初次分派与F13重分派严格分离。已经存在有效Assignment的工单再次调用F12会返回`409 Conflict`，不会覆盖原负责人或生成重复历史/通知。

## Role and Permission Matrix

| Capability | Requester | Maintenance Staff | Administrator |
|---|---:|---:|---:|
| View assignment on authorised ticket | Own | Assigned | All |
| View assigned ticket details/history | Own | Assigned only | All |
| List assignable technicians | No | No | Yes |
| Create initial assignment | No | No | Yes |

所有权限由后端按当前活动角色验证。未登录返回`401`，Requester或Maintenance Staff调用管理接口返回`403`。

## API

```text
GET /api/tickets/technicians
```

仅Administrator模式可用，只返回`account_status=ACTIVE`且获授`MAINTENANCE_STAFF`的用户；被分派人无需当前正打开维修模式。

```text
POST /api/tickets/{ticketId}/assignment
Content-Type: application/json

{
  "technicianId": 123
}
```

- 缺失、非正数、不存在、禁用或非Maintenance Staff的用户返回`400`。
- 已取消、已关闭或其他非`SUBMITTED`工单返回`409`。
- 已分派工单返回`409`，并明确要求使用F13 Reassignment workflow。

## Database Design

### `ticket_assignments`

每次Assignment保存Ticket、Technician、Assigned By、Assigned At、Active标志，并预留Ended At与Reason供F13使用。索引覆盖当前工单负责人和Technician工作区查询。

### `in_app_notifications`

成功分派生成`TICKET_ASSIGNED`事件，包括Recipient、Actor、Ticket、Message、Created At、Read At和唯一Deduplication Key。F12只负责产生事件，通知中心和已读操作属于F17。

### `ticket_history`

成功分派写入`TICKET_ASSIGNED`：

- Previous Value：`status=SUBMITTED | assignee=UNASSIGNED`
- New Value：`status=ASSIGNED | assigneeId=... | assignee=...`
- Actor：执行分派的Administrator
- Occurred At：服务器时间

## Front-end

- Administrator在`SUBMITTED + Unassigned`工单中看到Assign Technician控件。
- 下拉列表只显示有效Maintenance Staff。
- 派单界面右侧展示维修人员候选列表：先列当前无未完成派工者，再列仍有未完成派工者；组内按考虑样本数的评分综合分排序，并显示真实平均分、评价数和未完成派工数。管理员可查看评价详情，最终仍由管理员手动选择，也可分派给忙碌人员。“无未完成派工”不代表实时在岗。评分及审核规则见 [维修人员评分、评价与审核](Technician-Ratings-Reviews-CN.md)。
- 成功后卡片立即显示`ASSIGNED`、负责人和Assigned Time，初次分派按钮消失。
- Maintenance Staff进入`My Assigned Requests`，只看到当前分配给自己的工单。
- Requester可在自己的工单中查看分派结果，但没有分派控件。
- 工单进入`ASSIGNED`后，Requester的Edit和Cancel按钮消失。

## Acceptance Verification

| Acceptance criterion | Verification result |
|---|---|
| 只分派SUBMITTED工单 | 成功路径通过；CANCELLED和CLOSED均返回`409` |
| 状态、负责人和审计原子保存 | API、数据库表及重启验证一致 |
| Technician有效性 | 不存在、禁用和非Technician均返回`400` |
| 仅Administrator可分派 | Guest `401`；Requester、Technician `403` |
| 已分派工单必须使用F13 | 第二次初始分派返回`409`，无重复副作用 |
| 生成F17通知事件 | `in_app_notifications`存在唯一未读`TICKET_ASSIGNED`记录 |
| 角色数据可见性 | Assigned Technician只看到自己的已分派工单；其他Technician为`403` |

完整Gradle测试套件、旧数据库副本升级、真实前端角色流程和重启持久化验证均通过。
