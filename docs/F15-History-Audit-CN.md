# F15 — Status History and Audit Trail

## 已实现范围

F15为工单增加不可由普通用户直接修改的审计历史。当前以下操作会在同一数据库事务中写入历史：

- `TICKET_CREATED`
- `TICKET_UPDATED`
- `TICKET_CANCELLED`
- `IMAGE_ADDED`
- `IMAGE_REMOVED`

同时预留了Priority、Assignment、Reassignment、Status Change、Resolution和Reopen事件类型，供F11–F14及F18直接使用。

## 历史数据

每条 `ticket_history` 记录包含：

- Ticket ID
- Event Type
- Previous Value
- New Value
- Actor ID and Name
- Occurred Time
- Optional Reason

业务写入与历史写入位于同一个Transaction，任何一部分失败都会回滚整个操作。

## API

```text
GET /api/tickets/{ticketId}/history
```

- Requester只能查看自己工单的历史。
- Administrator和Manager可以查看全部工单历史。
- 其他无权用户返回 `403 Forbidden`。
- 历史按照时间和ID正序返回。

## Front-end

Requester工单卡片增加 `View History` 按钮。Timeline显示事件、操作者、时间、前值、新值和原因。

## Status Migration

- 新工单初始状态统一为 `SUBMITTED`。
- 启动时会显式升级H2 `tickets.status` 枚举约束，使数据库允许新的完整状态集合。
- 旧数据库中的 `PENDING` 会在启动时转换为 `SUBMITTED`。
- 现有但没有历史的工单会生成一条 `LEGACY_IMPORTED` 记录。
- `PENDING` 仅作为临时数据库兼容枚举保留，业务流程不会创建该状态。

## Verified Scenarios

- Create -> Update -> Cancel produces three ordered history events.
- A cancelled ticket cannot be edited and returns `409`.
- Another Requester cannot read the history and receives `403`.
- Administrator can read the history and receives `200`.
- Existing database copy starts successfully and receives legacy history backfill.
