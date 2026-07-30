# 占用侧持房

> **状态: Shipped（摘要页）** · 详细决策见 ADR。  
> 完整旧稿已归档：[`archive/特性 PRD/device-owned-command-room.md`](../archive/特性%20PRD/device-owned-command-room.md)

## 权威入口

| 文档 | 作用 |
|------|------|
| [`CONTEXT.md`](../../CONTEXT.md) | 占用房间 / roomReady |
| [`决策记录/0004-device-owned-command-room.md`](../决策记录/0004-device-owned-command-room.md) | 设备占用建房，Web 进房 |

## 现行要点

- 扫码绑定成功后设备 `ensure` 占用房并进 TRTC `HOLDING`
- Web 仅在 `roomReady` 后可监看/连线
- 业务结束回 `HOLDING`；解绑才退房
