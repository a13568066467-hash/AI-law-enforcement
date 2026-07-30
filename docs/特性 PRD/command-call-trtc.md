# 指挥连线（TRTC）

> **状态: Shipped（摘要页）** · 详细决策见 ADR，领域词见 CONTEXT。  
> 完整旧稿已归档：[`archive/特性 PRD/command-call-trtc.md`](../archive/特性%20PRD/command-call-trtc.md)

## 权威入口

| 文档 | 作用 |
|------|------|
| [`CONTEXT.md`](../../CONTEXT.md) | 指挥连线 / 连线房间 / 连线共摄 / 连线对讲 |
| [`决策记录/0002-trtc-for-command-calls.md`](../决策记录/0002-trtc-for-command-calls.md) | 媒体选型 TRTC |
| [`决策记录/0004-device-owned-command-room.md`](../决策记录/0004-device-owned-command-room.md) | 占用侧持房 |
| [`架构流程图/`](../架构流程图/README.md) | 现行系统结构 |

## 现行要点（对齐代码）

- 媒体：腾讯云 TRTC；信令：自建（设备侧当前以 HTTP poll 为主）
- 连线共摄：旁路本机循环录像，不另开相机
- 设备 PTT 半双工上行；Web「广播」开关麦
- **无**来电/接通语音播报（以状态灯与 UI 为准）
