# Physical PTT Wave Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Synchronize the AI assistant wave animation with physical PTT long-press activity through one SessionManager state source, while preserving physical PTT short-press white-light behavior.

**Architecture:** `SessionManager` exposes listening and processing state and notifies existing status listeners whenever either changes. `PttSnapAskController` writes physical long-press lifecycle into that state; `ChatFragment` resolves TTS, listening, and processing into one visual state.

**Tech Stack:** Kotlin, Android key dispatch, SessionManager status listeners, JUnit 4

## Global Constraints

- Physical PTT short press continues to toggle only the white light.
- Wave animation starts only after the existing 500ms long-press threshold.
- Screen PTT, physical PTT, and AI requests use the same SessionManager state.
- TTS has visual priority over listening, which has priority over processing.
- Do not modify microphone capture, ASR, recording, or video-call PTT behavior.

---

### Task 1: Test the unified visual-state resolver

**Files:**
- Modify: `android-app/app/src/main/kotlin/com/aifieldcam/app/ui/chat/AssistantWaveState.kt`
- Modify: `android-app/app/src/test/kotlin/com/aifieldcam/app/ui/chat/AssistantWaveStateTest.kt`

**Interfaces:**
- Produces: `AssistantWaveState.resolve(ttsSpeaking: Boolean, aiListening: Boolean, aiProcessing: Boolean): AssistantWaveState`

- [ ] **Step 1: Add failing priority tests**

Test idle, processing-only, listening-over-processing, and speaking-over-listening/processing.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `cd android-app; .\gradlew.bat :app:runUnitTestsInline --args="com.aifieldcam.app.ui.chat.AssistantWaveStateTest"`

Expected: compilation fails because `resolve` does not exist.

- [ ] **Step 3: Implement resolver**

```kotlin
companion object {
    fun resolve(ttsSpeaking: Boolean, aiListening: Boolean, aiProcessing: Boolean) = when {
        ttsSpeaking -> SPEAKING
        aiListening -> LISTENING
        aiProcessing -> PROCESSING
        else -> IDLE
    }
}
```

- [ ] **Step 4: Run focused tests to GREEN**

Expected: all `AssistantWaveStateTest` tests pass.

### Task 2: Expose and notify unified SessionManager state

**Files:**
- Modify: `android-app/app/src/main/kotlin/com/aifieldcam/app/data/SessionManager.kt`

**Interfaces:**
- Produces: `isAiListening(): Boolean`
- Produces: `isAiProcessing(): Boolean`

- [ ] **Step 1: Add read-only state accessors**

Return the existing private `aiListening` and `aiChatInFlight` fields without introducing duplicate storage.

- [ ] **Step 2: Notify when request processing starts and ends**

After every `aiChatInFlight = true/false` in expert and chat request paths, synchronize indicators and invoke the existing coalesced `notifyStatus()`.

- [ ] **Step 3: Compile**

Run: `cd android-app; .\gradlew.bat compileDebugKotlin`

Expected: `BUILD SUCCESSFUL`.

### Task 3: Write physical PTT lifecycle into SessionManager

**Files:**
- Modify: `android-app/app/src/main/kotlin/com/aifieldcam/app/platform/PttSnapAskController.kt`
- Modify: `android-app/app/src/test/kotlin/com/aifieldcam/app/platform/PttSnapAskControllerTest.kt` if a pure lifecycle seam is introduced.

**Interfaces:**
- Consumes: `SessionManager.setAiListening(Boolean)`

- [ ] **Step 1: Start listening only on controller entry**

Call `session.setAiListening(true)` inside `onPttDown`, which is invoked only after `RecorderKeyDispatcher` recognizes the 500ms long press. Do not touch the short-press branch.

- [ ] **Step 2: Clear listening on every terminal path**

Before clearing `pendingSession` in `reset`, call `pendingSession?.setAiListening(false)`. This covers release, timeout, cancellation, capture failure cleanup, and missing-session reset.

- [ ] **Step 3: Verify routing**

Confirm `RecorderKeyDispatcher` still schedules `onPttDown` after `LONG_PRESS_MS = 500L`, while short release continues to `session.toggleWhiteLight()`.

- [ ] **Step 4: Compile and run available PTT tests**

Expected: build and existing PTT tests pass.

### Task 4: Make Chat render the unified state

**Files:**
- Modify: `android-app/app/src/main/kotlin/com/aifieldcam/app/ui/chat/ChatFragment.kt`

**Interfaces:**
- Consumes: `AssistantWaveState.resolve(...)`
- Consumes: `SessionManager.isAiListening()`, `SessionManager.isAiProcessing()`

- [ ] **Step 1: Add one synchronization method**

```kotlin
private fun syncWaveState() {
    setWaveState(
        AssistantWaveState.resolve(
            ttsSpeaking = TtsSpeaker.isSpeaking(),
            aiListening = session.isAiListening(),
            aiProcessing = session.isAiProcessing(),
        ),
    )
}
```

- [ ] **Step 2: Synchronize all event sources**

TTS listener, `onTabVisible`, and `onSessionChanged` call `syncWaveState`. TTS false therefore reveals ongoing listening/processing instead of forcing IDLE.

- [ ] **Step 3: Keep screen touch behavior on the same source**

ACTION_DOWN/UP/CANCEL continue calling `session.setAiListening`; after request dispatch, call `syncWaveState` rather than maintaining a competing local-only state transition.

- [ ] **Step 4: Preserve lifecycle cleanup**

Hidden/destroyed pages stop the View, but do not erase a physical PTT state owned by SessionManager. Returning to the tab calls `syncWaveState`.

### Task 5: Defect and regression verification

**Files:**
- Review files changed in Tasks 1–4.

- [ ] **Step 1: Run focused and available unit tests**

Run state tests, PTT tests, and the repository's usable inline suite.

- [ ] **Step 2: Build**

Run: `cd android-app; .\gradlew.bat assembleDebug`

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Inspect critical cases**

Verify short physical press emits no listening state; long press starts after 500ms; release/cancel clears listening; processing notification follows dispatch; TTS completion reveals underlying state; hidden Chat does not clear global state; no callback accesses destroyed binding.

- [ ] **Step 4: Check diagnostics and diff**

Run IDE diagnostics and `git diff --check`.

- [ ] **Step 5: Device acceptance**

Test short press, long press from Chat, long press from another tab then return to Chat, release during frame capture, timeout, AI failure, and video-call PTT (which must remain unchanged).
