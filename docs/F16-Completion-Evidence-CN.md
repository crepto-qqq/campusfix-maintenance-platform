# F16 - Upload Completion Evidence

## 功能说明

维修人员在处理工单时，可以上传维修说明和完成后的图片作为维修证据。证据与工单、上传人员和上传时间关联，并可被有权限查看该工单的用户查看。

## API

- `POST /api/tickets/{ticketId}/completion-evidence`
  - `multipart/form-data`
  - `repairNotes`: 必填
  - `image`: 必填，必须为图片，最大 15 MB
- `GET /api/tickets/{ticketId}/completion-evidence`
- `GET /api/tickets/{ticketId}/completion-evidence/{evidenceId}/image`

## Acceptance Criteria

1. 只有已登录的 Maintenance Staff 可以上传完成证据。
2. 只有当前被分配到该工单的维修人员可以上传。
3. 只有 `IN_PROGRESS` 状态的工单可以上传完成证据。
4. Repair notes 和 evidence image 都必须提供。
5. 非图片文件或超过 15 MB 的图片会被拒绝。
6. 成功上传后，证据持久化保存并记录上传人员与时间。
7. 上传操作写入 `COMPLETION_EVIDENCE_ADDED` audit history。
8. Requester、assigned technician 和 Administrator 在有权查看工单时可以查看证据。
9. 其他无权访问该工单的用户不能通过 evidence endpoint 绕过 ticket access control。
10. Frontend 在维修工单处于 `IN_PROGRESS` 时提供 completion evidence 上传界面。

## Workflow

`ASSIGNED -> IN_PROGRESS -> Upload Completion Evidence -> COMPLETED`

当前实现没有强制“必须先上传 evidence 才能 Completed”，以保持现有 F14 status workflow 向后兼容。
