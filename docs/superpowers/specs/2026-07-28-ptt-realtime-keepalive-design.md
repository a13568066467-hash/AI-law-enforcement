# AI 助手 Realtime 预热保活（即按即用）

**日期:** 2026-07-28  
**状态:** 已批准，实现中

## 背景

物理 PTT /「按住说话」当前多在按下时才连接 `/v1/realtime/voice`，常先进入 `CONNECTING` 再收音，外勤体感不是「即按即用」。另有断联后采音未停、录像+HTTP 预览抢走 PTT 归属等问题，已另修。

## 目标

扫码绑定成功后预热 Realtime WebSocket，保持 `Ready`；按住立刻收音。掉线静默自动重连。解绑或指挥连线打断时断开；指挥挂断后若仍绑定则再次预热。

## 非目标

- 免按键 VAD 常开对话
- 后台开麦或后台推摄像头帧
- 预热失败时 TTS 刷屏
- 改后端百炼桥接协议

## 架构与生命周期

```
扫码绑定成功 / App 启动已占用
  └─ ensureWarm(session) → WS 连接 → Ready（保活，不占麦）

按住 PTT
  ├─ 已 Ready → 立刻 LISTENING + 开麦
  └─ 尚未 Ready → CONNECTING，Ready 后再采

掉线（保活开启）
  └─ 静默退避重连，不 TTS

解绑 / clearAuth
  └─ tearDown()

指挥连线开始
  └─ interruptForCommandCall()（撕断）

指挥连线结束且仍绑定
  └─ ensureWarm()（再次预热）
```

## 重连策略

| 项 | 约定 |
|----|------|
| 保活模式 | 无限退避：0.5s → 1s → 2s → 5s → 10s（封顶），直到成功 / tearDown / 指挥打断 |
| 提示 | 静默，仅日志 |
| 非保活 | 可保留短失败路径（按住现连且未开启保活时） |

## 按住语义

- 已 Ready：立刻 `beginCapture`
- 重连中按住：UI 可为 CONNECTING；首期等 Ready 再采（不本地预缓冲）
- 松手时仍未 Ready：停麦回 IDLE，本轮不 commit
- SPEAKING/THINKING 再按：打断并开新一轮（现逻辑）

## 接入点

| 时机 | 调用 |
|------|------|
| `applyBindSuccess` | `ensureWarm` |
| `restoreAuth`（启动已绑定） | `ensureWarm` |
| `clearBindLocal` / 解绑清态 | `tearDown` |
| 指挥来电 | 现有 `interruptForCommandCall` |
| `onCommandCallEnd` 且仍绑定 | `ensureWarm` |

## 失败边界

- 未绑定 / 无 token：不预热
- 预热失败：只日志；用户按住时再提示
- 共麦/录像规则不变
- 预热不阻塞绑定流程

## 验收

1. 绑机后未按键，日志可见 Realtime `Ready`
2. 绑机后立刻长按 PTT → 几乎无连接等待，直接收音
3. 断网恢复 → 静默重连后仍即按即用，无错误 TTS 刷屏
4. 解绑后 WS 断，按住提示需绑定
5. 指挥连线中不起 AI；挂断后自动再预热
6. 录像中共麦仍可用

## 改动面

- `RealtimeVoiceClient`：保活重连
- `PttSnapAskController`：`ensureWarm` / `tearDown`
- `SessionManager`：绑定 / 解绑 / 连线结束挂钩
- 单测：退避策略与「已 Ready 则按住直采」相关纯逻辑
