# AI Assistant Wave Animation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a simulated seven-bar wave animation to the AI assistant orb across listening, processing, and speaking states without adding status text or changing the audio pipeline.

**Architecture:** A focused `ListeningWaveView` owns drawing and animation. A pure `AssistantWaveState` transition policy is unit-tested, while `ChatFragment` maps touch, request, TTS, and lifecycle events to the view.

**Tech Stack:** Android custom View, Canvas, ValueAnimator, Kotlin, JUnit 4, View Binding

## Global Constraints

- Keep the button text exactly `按住说话` in every state.
- Do not add listening, processing, sending, or answering hint text.
- Use simulated animation; do not read microphone amplitude or alter the audio pipeline.
- Preserve the current quick-tap and hold/release command behavior.
- Stop animation on request failure, page hide, or view destruction.

---

### Task 1: Testable wave state policy

**Files:**
- Create: `android-app/app/src/main/kotlin/com/aifieldcam/app/ui/chat/AssistantWaveState.kt`
- Create: `android-app/app/src/test/kotlin/com/aifieldcam/app/ui/chat/AssistantWaveStateTest.kt`

**Interfaces:**
- Produces: `enum class AssistantWaveState { IDLE, LISTENING, PROCESSING, SPEAKING }`
- Produces: `AssistantWaveState.onPress()`, `onRelease()`, `onSpeechChanged(Boolean)`, `onRequestFinished(hasSpokenReply: Boolean)`, `onAbort()`

- [ ] **Step 1: Write transition tests**

Test press → LISTENING, release → PROCESSING, speech start → SPEAKING, speech end → IDLE, failed request → IDLE, and abort from every active state → IDLE.

- [ ] **Step 2: Run focused test and verify RED**

Run: `cd android-app; .\gradlew.bat testDebugUnitTest --tests "com.aifieldcam.app.ui.chat.AssistantWaveStateTest"`

Expected: FAIL because the state type does not exist.

- [ ] **Step 3: Implement the minimal immutable transitions**

```kotlin
enum class AssistantWaveState {
    IDLE, LISTENING, PROCESSING, SPEAKING;

    fun onPress() = LISTENING
    fun onRelease() = if (this == LISTENING) PROCESSING else this
    fun onSpeechChanged(speaking: Boolean) = if (speaking) SPEAKING else IDLE
    fun onRequestFinished(hasSpokenReply: Boolean) =
        if (hasSpokenReply) this else IDLE
    fun onAbort() = IDLE
}
```

- [ ] **Step 4: Run focused test to GREEN**

Use `runUnitTestsInline` if the known Windows Gradle Test Worker issue recurs. Expected: all state tests PASS.

### Task 2: Custom seven-bar wave view

**Files:**
- Create: `android-app/app/src/main/kotlin/com/aifieldcam/app/ui/chat/ListeningWaveView.kt`
- Modify: `android-app/app/src/main/res/layout/fragment_chat.xml`

**Interfaces:**
- Consumes: `AssistantWaveState`
- Produces: `ListeningWaveView.setState(state: AssistantWaveState)`
- Produces: `ListeningWaveView.stop()`

- [ ] **Step 1: Implement Canvas rendering**

Draw seven centered rounded bars. Use deterministic phase offsets and normalized sine values. LISTENING uses fast/high amplitude, PROCESSING slow/low amplitude, SPEAKING medium/high amplitude, IDLE stops and hides bars.

- [ ] **Step 2: Add one ValueAnimator**

Use a single repeating animator and invalidate the View each frame. `setState` must reuse the animator rather than stack instances. `onDetachedFromWindow` calls `stop()`.

- [ ] **Step 3: Replace active ripple layers in layout**

Place `ListeningWaveView` inside `orb_container`, above the orb background and static icon. The static icon is visible only in IDLE; the wave View is visible only in active states. Remove obsolete ripple Views after Chat integration is complete.

- [ ] **Step 4: Compile**

Run: `cd android-app; .\gradlew.bat compileDebugKotlin`

Expected: `BUILD SUCCESSFUL`.

### Task 3: Integrate touch, processing, TTS, and lifecycle

**Files:**
- Modify: `android-app/app/src/main/kotlin/com/aifieldcam/app/ui/chat/ChatFragment.kt`

**Interfaces:**
- Consumes: `AssistantWaveState`, `ListeningWaveView.setState`

- [ ] **Step 1: Add a single state setter**

```kotlin
private var waveState = AssistantWaveState.IDLE

private fun setWaveState(next: AssistantWaveState) {
    waveState = next
    binding.listeningWave.setState(next)
    binding.ivAssistantWave.visibility =
        if (next == AssistantWaveState.IDLE) View.VISIBLE else View.GONE
}
```

- [ ] **Step 2: Map touch events**

ACTION_DOWN enters LISTENING without changing button text. ACTION_UP enters PROCESSING and sends the existing phrase. ACTION_CANCEL enters IDLE and must not send a phrase.

- [ ] **Step 3: Map request and TTS events**

TTS speaking starts SPEAKING and speaking end returns IDLE. Request error or an empty non-spoken response returns IDLE. A reply that will be spoken remains active until the TTS listener reports completion.

- [ ] **Step 4: Clean legacy ripple implementation**

Remove `rippleAnimators`, `setVoiceRippleActive`, `startVoiceRipple`, `stopVoiceRipple`, and obsolete animation imports. `onTabHidden` and `onDestroyView` abort state and stop the View.

- [ ] **Step 5: Verify constant copy**

Search `ChatFragment.kt` and `fragment_chat.xml`; there must be no newly introduced “正在聆听/处理中/松开发送” labels, and `tv_ptt_label` remains `按住说话`.

### Task 4: Defect investigation and regression verification

**Files:**
- Review all files changed by Tasks 1–3.

**Interfaces:**
- Consumes: completed state policy and animation View.

- [ ] **Step 1: Run focused tests**

Run state tests through the available test runner. Expected: PASS.

- [ ] **Step 2: Run build and existing tests**

Run `assembleDebug` and the repository's usable inline unit-test suite. Record known environment-only failures separately.

- [ ] **Step 3: Inspect defect cases**

Verify no duplicate Animator after repeated presses; ACTION_CANCEL sends nothing; failure and empty response stop animation; TTS completion stops animation; tab hide/view destroy stops animation; callbacks guard null binding.

- [ ] **Step 4: Check diagnostics and diff**

Run IDE diagnostics and `git diff --check`. Expected: no new errors.

- [ ] **Step 5: Device acceptance**

On device, verify quick tap, long press/release, command failure, AI playback, repeated interaction, tab switching, and returning to AI assistant.
