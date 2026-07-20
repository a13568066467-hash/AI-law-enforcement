# Command Call Intercom (Issue 4) Implementation Plan

> **For agentic workers:** Use `/implement` + TDD. Status: Implemented 2026-07-20.

**Goal:** 连线对讲——指挥连线中 F6 长按上行、短按白光、松开停麦；录像共麦不抢第二路 AudioRecord。

**Architecture:** `CommandCallIntercomPolicy` 决定 F6 归属（连线 > stub 视频流 > AI）；`CommandCallIntercom` 经可注入 `CommandCallAudioCapture` 推 PCM 到 `CommandCallRoomAdapter`；默认静音半双工。

## Acceptance

- [x] 连线中长按 → 开麦并 push PCM（Fake 可观察）
- [x] 连线中短按语义 → 白光（由 Dispatcher 走 policy，不启 AI）
- [x] 松开 → 静音且不再收 PCM
- [x] 结束连线 → forceStop 对讲
- [x] 采音接缝可注入（测试不碰 AudioRecord）
