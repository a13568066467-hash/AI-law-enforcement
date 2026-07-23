# 现场事件工单 — Issues（本地）

> 父 PRD: [`docs/特性 PRD/field-event-ticket.md`](../特性%20PRD/field-event-ticket.md)  
> GitHub 发布受阻（需 `gh auth login`）；此处为 `/to-issues` 竖切，按序实现。

---

## Issue 1: 创建与查阅现场事件工单

**Blocked by:** None  
**User stories:** 15–16, 21–24  
**Status:** done

### Acceptance criteria

- [x] 有效输入可创建工单，正文非空，含设备/人员/时间/公司
- [x] 空/无效输入 4xx 且不落库
- [x] 列表/详情按公司隔离
- [x] Web 控制台可看本公司工单列表与详情

---

## Issue 2: 工单状态手改

**Blocked by:** Issue 1  
**User stories:** 17  
**Status:** done

### What to build

Web/API 可将工单状态在待处理 / 处理中 / 已关闭间更新（本公司范围内）。

### Acceptance criteria

- [x] PATCH 状态三态生效
- [x] 他公司不可改

---

## Issue 3: Android SOS 按住上报

**Blocked by:** Issue 1  
**User stories:** 1–14, 26–28  
**Status:** done

### What to build

SOS 按住收音（共麦）、松手上传创建工单；门禁与提示符合 PRD；去掉长按演示开录；短按重点标记保留。

### Acceptance criteria

- [x] 按住/松手路径接创建 API
- [x] 未绑定/连线中拒绝；监看/录像可报
- [x] 失败语音提示、不建空单
- [x] 长按不再跑 sos_emergency 开录

---

## Issue 4: 从工单发起指挥连线

**Blocked by:** Issue 1  
**User stories:** 18, 25  
**Status:** pending

### What to build

工单详情提供可选「发起指挥连线」，复用现有平台呼叫设备能力。

### Acceptance criteria

- [ ] 详情可对关联设备发起指挥连线
- [ ] 忙线/离线错误语义与现网一致
