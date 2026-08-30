# F11 — Ticket Priority Classification / 工单优先级分类

## 已实现范围

F11将Requester提交的`urgency`与实际调度使用的`operationalPriority`分离。`urgency`只表示提交者的建议；新工单的最终优先级默认为`null / Not set`，只能由Administrator完成初次分类或后续调整。

约定的Operational Priority枚举为：

- `LOW`
- `MEDIUM`
- `HIGH`
- `CRITICAL`

## Role and Permission Matrix

| Capability | Requester | Maintenance Staff | Administrator | Manager |
|---|---:|---:|---:|---:|
| View priority on an authorised ticket | Yes | Assigned workflow pending | Yes | Yes |
| View priority distribution | No | No | Yes | Yes |
| Set or change operational priority | No | No | Yes | No |
| View priority audit history | Own tickets | Assigned workflow pending | Yes | Yes |

权限由后端验证，隐藏前端按钮不是授权依据。未登录返回`401`；已登录但角色无权返回`403`。

## API

```text
PUT /api/tickets/{ticketId}/priority
Content-Type: application/json

{
  "priority": "HIGH",
  "reason": "Safety impact"
}
```

- 非法或缺失枚举返回`400 Bad Request`。
- 原因可省略，最多1000字符，保存前去除首尾空格。
- 重复设置相同优先级返回`409 Conflict`，不会制造无意义历史。

```text
GET /api/tickets/priority-summary
```

该管理汇总向Administrator和Manager返回`unclassified / low / medium / high / critical`数量，所有数量直接读取同一持久化字段，确保与工单列表和详情一致。

## Database and Audit

- `tickets.operational_priority`使用H2枚举并允许`NULL`，以区分“尚未分级”和`LOW`。
- 索引`idx_tickets_operational_priority`支持后续F10筛选及F20报表。
- 每次有效修改和`PRIORITY_CHANGED`历史记录在同一Transaction中写入。
- 历史包含Ticket ID、旧值、新值、Actor、Occurred Time和Optional Reason。
- 旧数据库启动时会扩展列约束；现有工单保持`NULL`，不会把Requester urgency错误转换为最终优先级。

## Front-end

- Requester工单卡片同时显示`Suggested urgency`和只读`Operational priority`。
- Administrator的All Requests中提供优先级下拉框、可选原因和Save Priority按钮。
- Manager看到相同列表与Priority Distribution，但没有修改控件。
- 保存成功后列表和Priority Distribution立即重新读取后端数据。

## Acceptance Verification

| Acceptance criterion | Verification result |
|---|---|
| Urgency与最终优先级分离 | 创建时注入客户端`operationalPriority`会被忽略；响应仍为`null` |
| 仅Administrator可修改 | 未登录`401`；Requester与Manager均`403`；前端无编辑控件 |
| 只接受约定枚举 | `URGENT`和`null`均返回`400` |
| 完整审计 | 已验证`UNSET → MEDIUM → HIGH`及Actor、Time、Reason |
| 列表、详情、汇总一致 | API集成测试与真实网页测试均通过 |
| 数据持久化和兼容升级 | 旧数据库副本升级成功，重启后Priority与History仍存在 |

完整Gradle测试套件执行结果：`BUILD SUCCESSFUL`。
