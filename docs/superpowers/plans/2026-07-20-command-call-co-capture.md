# Command Call Co-Capture (Issue 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Steps use checkbox (`- [x]`) syntax.

**Goal:** 连线共摄——指挥连线中本机 1080p 录像不停；约 720p 旁路帧注入 `CommandCallRoom`（Fake 可观察）；禁止二次 `openCamera`。

**Status:** Implemented 2026-07-20. Android `runUnitTestsInline` **54 passed**.

**Architecture:** 镜像 `PcmTeeBridge`：可注入帧源 → 纯函数缩放到长边≤1280 → `CommandCallRoomAdapter.pushVideoFrame`。真 TRTC 与 Bitmap 缩放可替换；单测用 Fake 帧源 + 维度数学，不接 Camera2/TRTC SDK。

**Tech Stack:** Kotlin, JUnit4, Issue 1–2 `CommandCallRoom*` / `CommandCallController`

## Global Constraints

- 不修改 `DeviceProfile` 录像分辨率（保持 1920×1080）。
- TRTC/旁路上行约 720p（长边 1280），非改本机录像。
- 禁止二次 `openCamera`；帧仅来自录像旁路或注入 Fake 源。
- 不断言 TRTC SDK 内部；不做像素比对。
- 依赖 Issue 2：Fake 进房已通。

## File Structure

| Path | Responsibility |
|------|----------------|
| `commandcall/CommandCallVideoFrame.kt` | 旁路帧 DTO + 目标尺寸常量 |
| `commandcall/CommandCallVideoScale.kt` | 纯函数：源尺寸 → ≤1280 长边 |
| `commandcall/CommandCallRoomAdapter.kt` | 扩展 `enableCustomVideoSource` / `pushVideoFrame` |
| `commandcall/FakeCommandCallRoomAdapter.kt` | 记录 pushed 帧供断言 |
| `commandcall/CommandCallCoCapture.kt` | 绑帧源→缩放→push；unbind 清理 |
| `commandcall/CommandCallFrameSource.kt` | 帧源接口 + Fake + Recording JPEG 源 |
| `commandcall/CommandCallController.kt` | 进房后 bind 共摄，退房前 unbind |
| `SessionManager.kt` | 进房成功且录像中时确保共摄；不调 grabSingleFrame |
| `*Test.kt` | 缩放 / Fake 收帧 / 共摄不二次开相机 / 结束后清理 |

---

### Task 1: Scale math + frame DTO + adapter API

**Files:** Create/Modify as above. Test: `CommandCallVideoScaleTest.kt`, update Fake tests.

- [ ] **Step 1: Failing tests**

```kotlin
@Test fun scales_1080p_to_720p_long_edge() {
  assertEquals(1280 to 720, CommandCallVideoScale.targetSize(1920, 1080, 1280))
}
@Test fun already_small_unchanged() {
  assertEquals(640 to 360, CommandCallVideoScale.targetSize(640, 360, 1280))
}
```

- [ ] **Step 2: Implement DTO + scale + adapter methods; Fake records frames**

```kotlin
data class CommandCallVideoFrame(
  val width: Int, val height: Int,
  val jpegBytes: ByteArray,
  val timestampMs: Long = System.currentTimeMillis(),
)
const val COMMAND_CALL_VIDEO_MAX_LONG_SIDE = 1280

interface CommandCallRoomAdapter {
  // existing...
  fun enableCustomVideoSource(enabled: Boolean)
  fun pushVideoFrame(frame: CommandCallVideoFrame)
}
```

Fake: `customVideoEnabled`, `pushedFrames: List`, clear on leave.

- [ ] **Step 3: GREEN**

---

### Task 2: CoCapture binder (injectable frame source)

**Files:** `CommandCallFrameSource.kt`, `CommandCallCoCapture.kt`, `CommandCallCoCaptureTest.kt`

```kotlin
fun interface CommandCallJpegScaler {
  fun scale(jpeg: ByteArray, targetW: Int, targetH: Int): ByteArray
}

interface CommandCallFrameSource {
  fun start(onFrame: (CommandCallVideoFrame) -> Unit)
  fun stop()
  /** 测试用：是否调用过会二次开相机的路径；生产 Recording 源恒为 false。 */
  fun openedSecondCamera(): Boolean = false
}

object CommandCallCoCapture {
  fun bind(adapter, source, scaler = identityOrProd)
  fun unbind()
  fun isActive(): Boolean
}
```

- [ ] **Step 1: Test — bind + source emits 1920×1080 → Fake receives ≤1280 long edge; openedSecondCamera false; unbind stops**

- [ ] **Step 2: Implement → GREEN**

Identity scaler for tests: return same bytes, CoCapture still sets width/height to targetSize.

---

### Task 3: Wire Controller + Recording frame source + SessionManager

**Files:** `CommandCallController.kt`, `RecordingCommandCallFrameSource.kt`, `SessionManager.kt`, `build.gradle.kts`

- Controller `onCallStart` success → `CommandCallCoCapture.bind` with injectable source factory (default: recording JPEG if `NativeRecorder.isRecording()`, else no-op source).
- `onCallEnd` → `unbind` then `leave`.
- `RecordingCommandCallFrameSource`: 定时/轮询 `NativeRecorder.grabRecordingFrame`；**永不**调用 `grabSingleFrame`。
- SessionManager: after successful join, if recording, ensure co-capture (Controller handles); on end unbind via Controller.

- [ ] **Step 1: Controller test with Fake source factory — start binds, end unbinds**

- [ ] **Step 2: Wire + register tests in `runUnitTestsInline`**

- [ ] **Step 3: Full suite GREEN**

**Out of scope:** 真 TRTC SDK、连线对讲 PTT、提高 ImageReader 原生 720p YUV surface（可用 JPEG 旁路+缩放满足 Issue 3 契约）。
