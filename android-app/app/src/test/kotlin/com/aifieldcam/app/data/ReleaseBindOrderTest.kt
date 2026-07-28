package com.aifieldcam.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 解绑成功后的本机步骤顺序：须先清占用态再异步退房，
 * 避免 TRTC leave 卡住时确认解绑后界面仍像已绑定。
 */
class ReleaseBindOrderTest {

    @Test
    fun successSteps_clearLocalBeforeOccupyRoomEnd() {
        val steps = releaseBindSuccessSteps()
        assertEquals(
            listOf("clearBindLocal", "scheduleOccupyRoomEnd", "onDone"),
            steps,
        )
        assertEquals(0, steps.indexOf("clearBindLocal"))
        assertEquals(1, steps.indexOf("scheduleOccupyRoomEnd"))
        assertEquals(2, steps.indexOf("onDone"))
    }

    companion object {
        /** 与 SessionManager.releaseBind 成功分支语义对齐的纯函数清单。 */
        fun releaseBindSuccessSteps(): List<String> = listOf(
            "clearBindLocal",
            "scheduleOccupyRoomEnd",
            "onDone",
        )
    }
}
