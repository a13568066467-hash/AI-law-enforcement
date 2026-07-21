# Command Call Real TRTC + Web Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (or subagent-driven-development) to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Android 真 TRTC 房间适配器 + React 指挥连线台，打通浏览器座席 ↔ 执法仪腾讯云音视频。

**Status:** Implemented 2026-07-21 (inline). Android unit tests 73 passed; `web-console` `npm run build` OK. Manual TRTC cloud联调待真机/密钥环境。

**Architecture:** `TrtcCommandCallRoomAdapter` 实现既有 `CommandCallRoomAdapter`（同步 join 等待进房、自定义视频/音频、strRoomId）；`AiFieldCamApplication` 默认注入。新建 `web-console/`（Vite+React+trtc-sdk-v5）调现有 command-call / dashboard API。后端 CORS 已 `*`，无需改。

**Tech Stack:** Kotlin, LiteAVSDK_TRTC 12.5.0.17575, FastAPI（已有）, Vite, React, TypeScript, trtc-sdk-v5

## Global Constraints

- 不改 Issues 1–6 信令/控制器契约；SecretKey 仅后端。
- 房间号用字符串 `strRoomId`（后端 `room-{call_id}`）。
- JVM 单测只碰 Fake；不在 CI 接腾讯云。
- 控制台仅指挥连线台；无登录。
- 用户未要求则不 git commit。

## File Structure

| Path | Responsibility |
|------|----------------|
| `android-app/.../TrtcCommandCallRoomAdapter.kt` | 真 TRTC 适配器 |
| `android-app/app/build.gradle.kts` | LiteAV 依赖 + abiFilters |
| `android-app/.../AiFieldCamApplication.kt` | `CommandCallRoom.use(Trtc…)` |
| `web-console/` | Vite React 指挥连线台 |
| `docs/superpowers/specs/2026-07-21-command-call-trtc-real-design.md` | 标记 Approved |

---

### Task 1: Android Real TRTC adapter + wire

**Files:**
- Create: `android-app/app/src/main/kotlin/com/aifieldcam/app/platform/commandcall/TrtcCommandCallRoomAdapter.kt`
- Modify: `android-app/app/build.gradle.kts`
- Modify: `android-app/app/src/main/kotlin/com/aifieldcam/app/AiFieldCamApplication.kt`

**Interfaces:**
- Implements: `CommandCallRoomAdapter`
- join timeout: 8 seconds; strRoomId from credentials.roomId

- [x] **Step 1: Add Gradle dependency + abiFilters**
- [x] **Step 2: Implement `TrtcCommandCallRoomAdapter`**
- [x] **Step 3: Wire Application**
- [x] **Step 4: Run existing Android unit tests** (73 passed)

---

### Task 2: Web console (Vite + React + TRTC)

**Files:**
- Create: `web-console/` (package.json, vite.config.ts, index.html, src/*)

**Interfaces:**
- GET `{API}/v1/dashboard/devices` → device list
- POST `{API}/v1/command-call/start` `{device_id, caller}` → `{call_id, platform:{sdk_app_id,room_id,user_id,user_sig}, status}`
- GET `{API}/v1/command-call/{call_id}` → status + failure_reason
- POST `{API}/v1/command-call/{call_id}/end`
- TRTC: `enterRoom({sdkAppId,userId,userSig,strRoomId})`, `startLocalAudio()`, remote video view; no local camera required for console

- [x] **Step 1: Scaffold Vite React TS + trtc-sdk-v5**
- [x] **Step 2: Implement CommandCall page**
- [x] **Step 3: `.env.example` with `VITE_API_BASE=http://127.0.0.1:8000`**
- [x] **Step 4: `npm install` && `npm run build` succeeds**

---

### Task 3: Docs + design status

- [x] Mark design Approved; note CORS already `*`；handoff 已更新

---

## Acceptance (manual)

1. Backend env TRTC configured; device occupied + polling.
2. Install APK with Real adapter; open web-console; Start call.
3. Platform sees remote video; mic works; device PTT uplink; End cleans up.
