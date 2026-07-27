# PTT Realtime 推摄像头帧 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按住 PTT 收音期间约 1fps 将本机摄像头 JPEG 经现有 Realtime WebSocket 桥送入百炼 Omni，松手后模型可凭画面回答型号/隐患。

**Architecture:** App 在 `LISTENING` 定时 `grabSnapshot` → 压缩 → JSON `image`；后端 `client_control` 译为 `input_image_buffer.append`；更新 `DEFAULT_INSTRUCTIONS` 使模型优先用推帧、工具作兜底。不改 TRTC、不开 VAD 免按键。

**Tech Stack:** Kotlin/Android（OkHttp WS）、FastAPI WebSocket 桥、`backend/app/realtime_voice.py`、pytest、Android JUnit。

## Global Constraints

- 仅 `LISTENING`（按住）推帧；思考/播报不推
- 目标 1 fps；上一帧未完成则跳过，不排队
- 先至少一包 PCM，再发图
- JPEG 约 480p 宽边；Base64 后 ≤256KB（原图建议 ≤190KB）；仍超则丢帧
- 单帧失败跳过，不断会话；0 帧仍可纯语音 commit
- 保留 `capture_and_explain` / 录像启停工具
- 指挥连线中仍禁止 AI（不改 `CommandCallAiPriority`）
- Spec: `docs/superpowers/specs/2026-07-27-ptt-realtime-camera-frames-design.md`

## File map

| File | Responsibility |
|------|----------------|
| `backend/app/realtime_voice.py` | `image_append`、解析 App `image`、改 instructions |
| `backend/tests/test_realtime_voice_protocol.py` | 协议与指令单测 |
| `android-app/.../RealtimeVoiceProtocol.kt` | `image(base64)` JSON |
| `android-app/.../RealtimeVoiceClient.kt` | `sendImage(jpeg)` |
| `android-app/.../RealtimeFrameCompressor.kt` | 缩放到 ~480p 并压到体积上限 |
| `android-app/.../PttSnapAskController.kt` | 定时抓帧泵 |
| `android-app/.../RealtimeVoiceProtocolTest.kt` | App 协议单测 |
| `android-app/.../RealtimeFrameCompressorTest.kt` | 空输入/边界 |
| `CONTEXT.md` | 领域词：PTT 推帧看现场 |

---

### Task 1: 后端协议桥接 + 指令

**Files:**
- Modify: `backend/app/realtime_voice.py`
- Create: `backend/tests/test_realtime_voice_protocol.py`

**Interfaces:**
- Produces: `RealtimeProtocol.image_append(image_b64: str) -> dict`
- Produces: `client_control` 接受 `{"type":"image","image":"..."}` → `[image_append(...)]`；空/缺字段 → `[]`
- Produces: 更新后的 `DEFAULT_INSTRUCTIONS`（不再声称看不到摄像头）

- [ ] **Step 1: Write the failing tests**

Create `backend/tests/test_realtime_voice_protocol.py`:

```python
"""RealtimeProtocol: image bridge + instructions."""
from __future__ import annotations

import sys
from pathlib import Path

BACKEND = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BACKEND))

from app.realtime_voice import DEFAULT_INSTRUCTIONS, RealtimeProtocol


def test_image_append_shape():
    ev = RealtimeProtocol.image_append("abc123")
    assert ev == {"type": "input_image_buffer.append", "image": "abc123"}


def test_client_control_image():
    events = RealtimeProtocol.client_control({"type": "image", "image": "Zm9v"})
    assert events == [
        {"type": "input_image_buffer.append", "image": "Zm9v"},
    ]


def test_client_control_image_missing_drops():
    assert RealtimeProtocol.client_control({"type": "image"}) == []
    assert RealtimeProtocol.client_control({"type": "image", "image": ""}) == []
    assert RealtimeProtocol.client_control({"type": "image", "image": "   "}) == []


def test_instructions_prefer_streamed_frames():
    assert "看不到摄像头" not in DEFAULT_INSTRUCTIONS
    assert "画面帧" in DEFAULT_INSTRUCTIONS or "连续画面" in DEFAULT_INSTRUCTIONS
    assert "capture_and_explain" in DEFAULT_INSTRUCTIONS
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && python -m pytest tests/test_realtime_voice_protocol.py -v`

Expected: FAIL（`image_append` 不存在 / instructions 仍含「看不到摄像头」）

- [ ] **Step 3: Implement**

In `backend/app/realtime_voice.py`:

1. Replace `DEFAULT_INSTRUCTIONS` with:

```python
DEFAULT_INSTRUCTIONS = (
    "你是赢筑 AI 现场助手。使用简短、口语化中文回答。"
    "用户按住说话期间，你会收到与语音时间轴对齐的连续画面帧；"
    "回答眼前/现场/型号/铭牌/仪表/隐患等问题时，优先依据这些画面帧。"
    "仅当画面缺失、模糊、被遮挡或仍无法判断时，再调用 capture_and_explain"
    "（把用户原话放入 question），等工具返回后再根据 explanation 回答；"
    "禁止在未看过画面帧且未调用该工具前说「看不到」「无法查看」「我没有视觉」之类的话。"
    "开始/停止录像仅在用户明确要求时分别调用 start_recording / stop_recording。"
)
```

2. Add to `RealtimeProtocol`:

```python
@staticmethod
def image_append(image_b64: str) -> dict[str, str]:
    return {
        "type": "input_image_buffer.append",
        "image": image_b64,
    }
```

3. In `client_control`, before the final `raise`, add:

```python
if kind == "image":
    image = str(message.get("image", "")).strip()
    if not image:
        return []
    # 约 256KB Base64 上限；过大丢弃，不断会话
    if len(image) > 256 * 1024:
        return []
    return [RealtimeProtocol.image_append(image)]
```

（若项目已有 logging，可对 oversized 打 `warning`；无则静默丢弃即可。）

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && python -m pytest tests/test_realtime_voice_protocol.py -v`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/app/realtime_voice.py backend/tests/test_realtime_voice_protocol.py
git commit -m "feat(backend): Realtime 桥接 input_image_buffer 并更新视觉指令"
```

---

### Task 2: Android 紧凑协议发图

**Files:**
- Modify: `android-app/app/src/main/kotlin/com/aifieldcam/app/platform/RealtimeVoiceProtocol.kt`
- Modify: `android-app/app/src/main/kotlin/com/aifieldcam/app/platform/RealtimeVoiceClient.kt`
- Modify: `android-app/app/src/test/kotlin/com/aifieldcam/app/platform/RealtimeVoiceProtocolTest.kt`

**Interfaces:**
- Consumes: Task 1 的 App→后端 `{"type":"image","image":"<b64>"}`
- Produces: `RealtimeVoiceProtocol.image(jpegBase64: String): String`
- Produces: `RealtimeVoiceClient.sendImage(jpeg: ByteArray): Boolean`（空数组返回 false；Base64 NO_WRAP）

- [ ] **Step 1: Write the failing test**

Append to `RealtimeVoiceProtocolTest.kt`:

```kotlin
@Test
fun imageEventJson() {
    val json = org.json.JSONObject(RealtimeVoiceProtocol.image("Zm9vYmFy"))
    assertEquals("image", json.getString("type"))
    assertEquals("Zm9vYmFy", json.getString("image"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android-app && .\gradlew.bat :app:testDebugUnitTest --tests com.aifieldcam.app.platform.RealtimeVoiceProtocolTest`

Expected: FAIL（`image` unresolved）

- [ ] **Step 3: Implement protocol + client**

In `RealtimeVoiceProtocol.kt` add:

```kotlin
fun image(jpegBase64: String): String =
    JSONObject()
        .put("type", "image")
        .put("image", jpegBase64)
        .toString()
```

In `RealtimeVoiceClient.kt` add (near `sendAudio`):

```kotlin
fun sendImage(jpeg: ByteArray): Boolean {
    if (jpeg.isEmpty()) return false
    val b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
    return socket?.send(RealtimeVoiceProtocol.image(b64)) == true
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: same gradle command as Step 2  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android-app/app/src/main/kotlin/com/aifieldcam/app/platform/RealtimeVoiceProtocol.kt \
  android-app/app/src/main/kotlin/com/aifieldcam/app/platform/RealtimeVoiceClient.kt \
  android-app/app/src/test/kotlin/com/aifieldcam/app/platform/RealtimeVoiceProtocolTest.kt
git commit -m "feat(android): Realtime 紧凑协议支持发送 JPEG 帧"
```

---

### Task 3: JPEG 压缩到 Realtime 上限

**Files:**
- Create: `android-app/app/src/main/kotlin/com/aifieldcam/app/platform/RealtimeFrameCompressor.kt`
- Create: `android-app/app/src/test/kotlin/com/aifieldcam/app/platform/RealtimeFrameCompressorTest.kt`

**Interfaces:**
- Produces: `RealtimeFrameCompressor.compressForRealtime(jpeg: ByteArray): ByteArray?`
  - 空输入 → `null`
  - 目标宽边 ≤ 480；质量从 70 递减到 40；原始字节 ≤ 190_000；仍超 → `null`
  - 解码失败 → `null`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.aifieldcam.app.platform

import org.junit.Assert.assertNull
import org.junit.Test

class RealtimeFrameCompressorTest {
    @Test
    fun emptyReturnsNull() {
        assertNull(RealtimeFrameCompressor.compressForRealtime(ByteArray(0)))
    }

    @Test
    fun garbageReturnsNull() {
        assertNull(RealtimeFrameCompressor.compressForRealtime(byteArrayOf(1, 2, 3, 4)))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android-app && .\gradlew.bat :app:testDebugUnitTest --tests com.aifieldcam.app.platform.RealtimeFrameCompressorTest`

Expected: FAIL（class missing）

- [ ] **Step 3: Implement compressor**

Create `RealtimeFrameCompressor.kt`:

```kotlin
package com.aifieldcam.app.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 将抓拍 JPEG 压到 Qwen Omni Realtime 建议体积（编码前 ≤190KB，约 480p）。
 */
internal object RealtimeFrameCompressor {
    private const val MAX_SIDE = 480
    private const val MAX_RAW_BYTES = 190_000
    private val QUALITIES = intArrayOf(70, 55, 40)

    fun compressForRealtime(jpeg: ByteArray): ByteArray? {
        if (jpeg.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        var longest = max(bounds.outWidth, bounds.outHeight)
        while (longest / sample > MAX_SIDE * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return null
        val scaled = scaleToMaxSide(decoded, MAX_SIDE)
        if (scaled !== decoded) decoded.recycle()

        try {
            for (q in QUALITIES) {
                val out = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, q, out)) continue
                val bytes = out.toByteArray()
                if (bytes.size in 1..MAX_RAW_BYTES) return bytes
            }
            return null
        } finally {
            scaled.recycle()
        }
    }

    private fun scaleToMaxSide(src: Bitmap, maxSide: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxSide) return src
        val ratio = maxSide.toFloat() / longest
        val w = (src.width * ratio).roundToInt().coerceAtLeast(1)
        val h = (src.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
```

Note: `BitmapFactory` 在纯 JVM unit test 对 garbage 常返回 null（`outWidth<=0`），与 `garbageReturnsNull` 一致；若本地 Robolectric 行为不同，以「解码失败 → null」为准。

- [ ] **Step 4: Run tests to verify they pass**

Run: same as Step 2  
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add android-app/app/src/main/kotlin/com/aifieldcam/app/platform/RealtimeFrameCompressor.kt \
  android-app/app/src/test/kotlin/com/aifieldcam/app/platform/RealtimeFrameCompressorTest.kt
git commit -m "feat(android): Realtime 推帧 JPEG 压缩到体积上限"
```

---

### Task 4: PTT 控制器接入 1fps 推帧

**Files:**
- Modify: `android-app/app/src/main/kotlin/com/aifieldcam/app/platform/PttSnapAskController.kt`

**Interfaces:**
- Consumes: `RealtimeVoiceClient.sendImage`、`RealtimeFrameCompressor.compressForRealtime`、`SessionManager.grabSnapshot`
- Produces: 按住 LISTENING 期间约每秒一帧；停采路径全部 `stopFramePump()`

- [ ] **Step 1: Add frame-pump fields and helpers**

In `PttSnapAskController` companion-level object, add:

```kotlin
private const val FRAME_INTERVAL_MS = 1_000L

private var framePumpRunning = false
private var frameInFlight = false
private var audioGateOpened = false

private val frameTick = object : Runnable {
    override fun run() {
        if (!framePumpRunning || !pressed || phase != RealtimeVoicePhase.LISTENING) {
            stopFramePump()
            return
        }
        if (!audioGateOpened || frameInFlight) {
            mainHandler.postDelayed(this, FRAME_INTERVAL_MS)
            return
        }
        val session = pendingSession
        if (session == null) {
            stopFramePump()
            return
        }
        frameInFlight = true
        session.grabSnapshot { jpeg ->
            mainHandler.post {
                try {
                    if (!framePumpRunning || !pressed) return@post
                    if (jpeg != null && jpeg.isNotEmpty()) {
                        val compressed = RealtimeFrameCompressor.compressForRealtime(jpeg)
                        if (compressed != null) {
                            client?.sendImage(compressed)
                        }
                    }
                } finally {
                    frameInFlight = false
                    if (framePumpRunning && pressed) {
                        mainHandler.postDelayed(frameTick, FRAME_INTERVAL_MS)
                    }
                }
            }
        }
    }
}

private fun startFramePump() {
    stopFramePump()
    framePumpRunning = true
    frameInFlight = false
    mainHandler.post(frameTick)
}

private fun stopFramePump() {
    framePumpRunning = false
    frameInFlight = false
    mainHandler.removeCallbacks(frameTick)
}
```

- [ ] **Step 2: Gate frames after first PCM; start/stop with capture lifecycle**

In `beginCapture`, change `VoiceCaptureHelper.startStreaming` so first PCM opens the gate:

```kotlin
VoiceCaptureHelper.startStreaming(
    onPcm = { pcm ->
        val sent = client?.sendAudio(pcm) == true
        if (sent && !audioGateOpened) {
            audioGateOpened = true
            startFramePump()
        }
    },
    onStarted = {
        audioGateOpened = false
        mainHandler.removeCallbacks(maxVoiceTimeout)
        mainHandler.postDelayed(maxVoiceTimeout, MAX_VOICE_MS)
    },
    onError = { error -> fail(pendingSession, error) },
)
```

Call `stopFramePump()` + `audioGateOpened = false` in:

- `onPttUp`（LISTENING 分支开头，在 `stopStreaming` 前）
- `cancel`
- `interruptForCommandCall`
- `fail`

- [ ] **Step 3: Compile check**

Run: `cd android-app && .\gradlew.bat :app:compileDebugKotlin`

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add android-app/app/src/main/kotlin/com/aifieldcam/app/platform/PttSnapAskController.kt
git commit -m "feat(android): PTT LISTENING 期间 1fps 推摄像头帧到 Realtime"
```

---

### Task 5: 领域词 + 设计状态

**Files:**
- Modify: `CONTEXT.md`（在「PTT 与录像共麦」附近新增或扩展 AI 实时看现场说明）
- Modify: `docs/superpowers/specs/2026-07-27-ptt-realtime-camera-frames-design.md` 状态改为「已批准，实现中」或完成后「已实现」

- [ ] **Step 1: Update CONTEXT.md**

在 `**PTT 与录像共麦**` 之后插入：

```markdown
**PTT 实时看现场**：
按住 AI 助手 PTT 收音期间，本机约每秒向实时语音会话推送一帧摄像头 JPEG，模型可直接依据画面回答型号/隐患等；松手提交后停止推帧。不是 TRTC 监看/连线画面，也不等于录像后抽帧分析。抓拍工具 `capture_and_explain` 仅作画面不足时的兜底。
_Avoid_: 免按键常开推帧、TRTC 轨直喂 Realtime、视频抽帧分析（相册/录像后）
```

- [ ] **Step 2: Flip spec status**

Set design doc header `**状态:** 已实现`（若本任务在代码合入后执行；若仅文档先行则写「已批准」）。

- [ ] **Step 3: Commit**

```bash
git add CONTEXT.md docs/superpowers/specs/2026-07-27-ptt-realtime-camera-frames-design.md
git commit -m "docs: 记录 PTT 实时看现场领域词与设计状态"
```

---

### Task 6: 真机验收（手测清单）

**Files:** 无代码（对照 spec 验收）

- [ ] **Step 1: 部署**

- 后端加载新 `realtime_voice`（含 Workspace/Key）
- `.\gradlew.bat installDebug` 装到 DSJ

- [ ] **Step 2: 跑验收剧本**

1. 绑机，对准铭牌，按住 PTT 问「什么型号」→ 松手有依据画面的回答或明确看不清  
2. Logcat 过滤 `PttRealtimeVoice` / 后端日志：按住约每秒 image；松手后停  
3. 未录像也能抓帧（DSJ）；指挥连线中 PTT 不起 AI  
4. 遮镜头问隐患 → 不瞎编读数  
5. 弱网丢帧 → 会话不崩，仍能口语答  

- [ ] **Step 3: 若失败**

优先查：是否先发了 PCM 再发图；压缩是否全丢；后端是否仍旧 instructions。修完再手测，不另开范围。

---

## Self-review (plan vs spec)

| Spec 要求 | Task |
|-----------|------|
| 1fps LISTENING 推帧 | Task 4 |
| JSON `image` + `input_image_buffer.append` | Task 1–2 |
| 先音频后图 | Task 4 audioGate |
| 480p / ≤190KB / 丢帧不断会话 | Task 1 size drop + Task 3 |
| 改 instructions，保留工具 | Task 1 |
| 指挥连线优先级不变 | Task 4 未改 AiPriority |
| 验收剧本 | Task 6 |
| CONTEXT 领域 | Task 5 |
| 非目标（TRTC/VAD） | 无任务触碰 |

No TBD placeholders. Types consistent: `sendImage(ByteArray)` ↔ compressor `ByteArray?` ↔ protocol base64 string.
