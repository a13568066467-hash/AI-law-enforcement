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
        if (CommandCallAiPriority.shouldInterruptAiOnCallStart()) {
            interruptCount += 1
            interruptAi()
        }
    }

    fun onCallEnd() {
        if (CommandCallAiPriority.shouldResumeAiAfterCallEnd()) {
            resumeCount += 1
            resumeAi()
        }
    }

    fun resetCountsForTests() {
        interruptCount = 0
        resumeCount = 0
    }
}
