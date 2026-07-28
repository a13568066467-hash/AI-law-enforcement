package com.aifieldcam.app.platform.commandcall

/**
 * 指挥连线相对 AI 全双工的优先级（PRD：来电打断 AI，结束后不自动恢复）。
 */
object CommandCallAiPriority {
    fun shouldInterruptAiOnCallStart(): Boolean = true

    fun shouldResumeAiAfterCallEnd(): Boolean = false

    /** 连线进行中禁止新开 AI 实时语音。 */
    fun allowsAiRealtime(commandCallActive: Boolean): Boolean = !commandCallActive
}

/**
 * 来电即置位、挂断/进房失败清除：盖住「先 interrupt、后异步 isInCall=true」空窗，
 * 避免空窗内再次拉起 AI。
 */
object CommandCallAiSuppressLatch {
    @Volatile
    private var armed = false

    fun arm() {
        armed = true
    }

    fun clear() {
        armed = false
    }

    fun isArmed(): Boolean = armed

    /** 门闩或已进房连线 → 禁止 AI。 */
    fun blocksAiRealtime(inCall: Boolean): Boolean = armed || inCall

    fun resetForTests() {
        armed = false
    }
}

/**
 * 可观察的打断/恢复门闩：Session 在进房前调用 [onCallStart]，结束时调用 [onCallEnd]。
 * 默认不恢复 AI；单测可注入计数钩子。
 */
class CommandCallAiGate(
    private val interruptAi: () -> Unit,
    private val resumeAi: () -> Unit = {},
) {
    @Volatile
    var interruptCount: Int = 0
        private set

    @Volatile
    var resumeCount: Int = 0
        private set

    fun onCallStart() {
        CommandCallAiSuppressLatch.arm()
        if (CommandCallAiPriority.shouldInterruptAiOnCallStart()) {
            interruptCount += 1
            interruptAi()
        }
    }

    fun onCallEnd() {
        CommandCallAiSuppressLatch.clear()
        if (CommandCallAiPriority.shouldResumeAiAfterCallEnd()) {
            resumeCount += 1
            resumeAi()
        }
    }

    /** 进房/升级失败：清门闩，由 Session 再 ensureWarm。 */
    fun onCallStartFailed() {
        CommandCallAiSuppressLatch.clear()
    }

    fun resetCountsForTests() {
        interruptCount = 0
        resumeCount = 0
        CommandCallAiSuppressLatch.resetForTests()
    }
}
