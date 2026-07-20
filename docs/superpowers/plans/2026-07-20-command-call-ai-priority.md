# Command Call AI Priority (Issue 5)

> Status: Implemented 2026-07-20

**Goal:** 指挥来电打断 AI 全双工 Realtime；结束后不自动恢复；连线中禁止新开 AI 语音。

**Seams:** `CommandCallAiPriority` / `CommandCallAiGate`；`PttSnapAskController.interruptForCommandCall()`；`SessionManager.onCommandCallStart` 先打断再进房。
