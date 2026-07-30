# 画面监看

> **状态: Shipped（摘要页）** · 详细决策见 ADR。  
> 完整旧稿已归档：[`archive/特性 PRD/live-preview-watch.md`](../archive/特性%20PRD/live-preview-watch.md)

## 权威入口

| 文档 | 作用 |
|------|------|
| [`CONTEXT.md`](../../CONTEXT.md) | 画面监看术语 |
| [`决策记录/0003-live-preview-session.md`](../决策记录/0003-live-preview-session.md) | 监看会话决策 |
| [`决策记录/0002-trtc-for-command-calls.md`](../决策记录/0002-trtc-for-command-calls.md) | 与连线共用 TRTC 媒体 |

## 现行要点

- 监看与指挥连线互斥；可升级为连线
- 不打断 AI；不算 `IN_CALL`
- Web：`/v1/command-call/watch/*`
