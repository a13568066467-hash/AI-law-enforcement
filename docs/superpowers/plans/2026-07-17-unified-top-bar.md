# Unified Top Bar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Album and Me main pages use the same brand, recording-status, and battery top bar as Home while preserving the system status bar and bottom navigation.

**Architecture:** Extract the duplicated Home/Chat top bar XML into one included layout and centralize its dynamic state binding in a focused Kotlin helper. Home, Chat, Album, and Me consume the same layout and binder; child settings pages keep their existing return headers.

**Tech Stack:** Android XML, Kotlin, View Binding, JUnit 4, Gradle

## Global Constraints

- Preserve the Android system status bar and the four-item bottom navigation.
- Album media grid fills only the remaining tab content area.
- Do not restore the remote shutter button.
- Do not change photo preview, MP4 playback, QR binding, or child settings-page behavior.

---

### Task 1: Shared top-bar state and layout

**Files:**
- Create: `android-app/app/src/main/res/layout/include_themis_top_bar.xml`
- Create: `android-app/app/src/main/kotlin/com/aifieldcam/app/ui/common/ThemisTopBar.kt`
- Create: `android-app/app/src/test/kotlin/com/aifieldcam/app/ui/common/ThemisTopBarTest.kt`

**Interfaces:**
- Produces: `ThemisTopBar.shouldShowRecording(recording: Boolean, recorderBusy: Boolean, videoSaving: Boolean): Boolean`
- Produces: `ThemisTopBar.bind(session: SessionManager, statusDot: View, statusText: TextView, batteryText: TextView, context: Context)`

- [ ] **Step 1: Write the failing state-policy test**

```kotlin
class ThemisTopBarTest {
    @Test fun recordingStateIsVisible() {
        assertTrue(ThemisTopBar.shouldShowRecording(true, false, false))
        assertTrue(ThemisTopBar.shouldShowRecording(false, true, false))
    }

    @Test fun idleAndSavingStatesAreHidden() {
        assertFalse(ThemisTopBar.shouldShowRecording(false, false, false))
        assertFalse(ThemisTopBar.shouldShowRecording(false, true, true))
    }
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `cd android-app; .\gradlew.bat testDebugUnitTest --tests "com.aifieldcam.app.ui.common.ThemisTopBarTest"`

Expected: FAIL because `ThemisTopBar` does not exist.

- [ ] **Step 3: Add the shared layout and binder**

The layout must reproduce Home's existing two-part brand, recording dot/text, battery icon, dimensions, colors, and connected-chip background. Implement:

```kotlin
object ThemisTopBar {
    fun shouldShowRecording(recording: Boolean, recorderBusy: Boolean, videoSaving: Boolean): Boolean =
        recording || (recorderBusy && !videoSaving)

    fun bind(
        session: SessionManager,
        statusDot: View,
        statusText: TextView,
        batteryText: TextView,
        context: Context,
    ) {
        val active = shouldShowRecording(
            session.isRecording(),
            session.isRecorderBusy(),
            session.isVideoSaving(),
        )
        statusDot.visibility = if (active) View.VISIBLE else View.GONE
        statusText.visibility = if (active) View.VISIBLE else View.GONE
        statusText.text = if (active) "录制中" else ""
        batteryText.text = batteryPercentText(context)
    }
}
```

Battery reads `Intent.ACTION_BATTERY_CHANGED`; invalid level/scale returns `--`.

- [ ] **Step 4: Re-run the focused test**

Run: `cd android-app; .\gradlew.bat testDebugUnitTest --tests "com.aifieldcam.app.ui.common.ThemisTopBarTest"`

Expected: PASS.

### Task 2: Migrate Home and Chat to the shared component

**Files:**
- Modify: `android-app/app/src/main/res/layout/fragment_home.xml`
- Modify: `android-app/app/src/main/res/layout/fragment_chat.xml`
- Modify: `android-app/app/src/main/kotlin/com/aifieldcam/app/ui/home/HomeFragment.kt`
- Modify: `android-app/app/src/main/kotlin/com/aifieldcam/app/ui/chat/ChatFragment.kt`

**Interfaces:**
- Consumes: `ThemisTopBar.bind(...)`
- Produces: View Binding property `binding.themisTopBar`

- [ ] **Step 1: Replace each duplicated top-bar XML block**

```xml
<include
    android:id="@+id/themis_top_bar"
    layout="@layout/include_themis_top_bar" />
```

- [ ] **Step 2: Replace page-specific recording/battery logic**

```kotlin
ThemisTopBar.bind(
    session,
    binding.themisTopBar.statusDot,
    binding.themisTopBar.tvStatus,
    binding.themisTopBar.tvBattery,
    requireContext(),
)
```

Remove obsolete battery and broadcast imports/functions.

- [ ] **Step 3: Compile**

Run: `cd android-app; .\gradlew.bat compileDebugKotlin`

Expected: `BUILD SUCCESSFUL`.

### Task 3: Add the shared bar to Album and Me

**Files:**
- Modify: `android-app/app/src/main/res/layout/fragment_album.xml`
- Modify: `android-app/app/src/main/kotlin/com/aifieldcam/app/ui/album/AlbumFragment.kt`
- Modify: `android-app/app/src/main/res/layout/fragment_me.xml`
- Modify: `android-app/app/src/main/kotlin/com/aifieldcam/app/ui/settings/MeFragment.kt`

**Interfaces:**
- Consumes: `ThemisTopBar.bind(...)`

- [ ] **Step 1: Structure Album as top bar plus weighted media content**

Use the same screen paddings as Home. Put the shared include first and a `FrameLayout` with `layout_height="0dp"` and `layout_weight="1"` below it. Keep only `rv_album` and `tv_empty` inside the frame.

- [ ] **Step 2: Replace Me's title**

Change Me hub paddings to the shared screen dimensions, remove the standalone `我的` heading, and insert `include_themis_top_bar`. Leave `me_child_container` unchanged.

- [ ] **Step 3: Bind both pages on visible/session refresh**

Add `refreshTopBar()` to both fragments and invoke it from `onViewCreated`, `onTabVisible`, and `onSessionChanged`. `MeFragment.refreshProfile()` also invokes it so return from a child page refreshes the header.

- [ ] **Step 4: Compile resources and Kotlin**

Run: `cd android-app; .\gradlew.bat assembleDebug`

Expected: `BUILD SUCCESSFUL`.

### Task 4: Regression verification

**Files:**
- Verify only; no additional production files expected.

**Interfaces:**
- Consumes: completed shared top bar.

- [ ] **Step 1: Run Android unit tests**

Run: `cd android-app; .\gradlew.bat testDebugUnitTest`

Expected: all tests PASS.

- [ ] **Step 2: Check IDE diagnostics**

Check the new helper, Home, Chat, Album, Me, and their layouts. Expected: no new diagnostics.

- [ ] **Step 3: Manual device acceptance**

Verify Home, AI, Album, and Me show identical top bars; recording state and battery agree; Album retains system status and bottom navigation; photo preview and MP4 playback still open; Me child pages retain their return headers.
