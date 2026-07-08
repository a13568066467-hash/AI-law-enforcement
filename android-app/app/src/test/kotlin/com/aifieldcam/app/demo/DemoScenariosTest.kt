package com.aifieldcam.app.demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DemoScenariosTest {

    @Test
    fun match_pre_shift_briefing() {
        assertEquals("pre_shift_briefing", DemoScenarios.matchFromText("开启班前安全交底"))
        assertEquals("pre_shift_briefing", DemoScenarios.matchFromText("班前指导开始"))
    }

    @Test
    fun match_hazard_supervision() {
        assertEquals("hazard_supervision", DemoScenarios.matchFromText("这里有什么隐患"))
        assertEquals("hazard_supervision", DemoScenarios.matchFromText("识别违规行为"))
        assertEquals("hazard_supervision", DemoScenarios.matchFromText("未戴安全帽"))
    }

    @Test
    fun match_expert_call() {
        assertEquals("expert_call", DemoScenarios.matchFromText("呼叫技术专家"))
        assertEquals("expert_call", DemoScenarios.matchFromText("远程指导"))
    }

    @Test
    fun match_device_inspection() {
        assertEquals("device_inspection", DemoScenarios.matchFromText("检测设备状态"))
        assertEquals("device_inspection", DemoScenarios.matchFromText("点位设备"))
    }

    @Test
    fun match_quality_inspection() {
        assertEquals("quality_inspection", DemoScenarios.matchFromText("请进行质量核验"))
        assertEquals("quality_inspection", DemoScenarios.matchFromText("蓝牙测量"))
    }

    @Test
    fun match_sos_emergency() {
        assertEquals("sos_emergency", DemoScenarios.matchFromText("SOS紧急求助"))
        assertEquals("sos_emergency", DemoScenarios.matchFromText("一键应急"))
    }

    @Test
    fun match_visual_question_no_scene() {
        // 通用视觉问答不应该匹配场景
        assertNull(DemoScenarios.matchFromText("这个阀门怎么操作"))
        assertNull(DemoScenarios.matchFromText("识别这个设备的型号"))
        assertNull(DemoScenarios.matchFromText("当前画面有什么问题"))
    }

    @Test
    fun match_empty_and_blank() {
        assertNull(DemoScenarios.matchFromText(""))
        assertNull(DemoScenarios.matchFromText("   "))
    }

    @Test
    fun all_scenes_count() {
        assertEquals(9, DemoScenarios.all.size)
    }
}