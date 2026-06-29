package com.aifieldcam.app.demo

/** 智慧工地九大场景 — 离线演示数据 */
object DemoScenarios {

    data class SceneMeta(
        val id: String,
        val title: String,
        val subtitle: String,
        val pttHint: String,
    )

    data class SceneResult(
        val scenarioId: String,
        val title: String,
        val voiceBroadcast: String,
        val reply: String,
        val document: String,
        val highlights: List<String>,
        val platformSync: String,
        val bleCmds: List<Int>,
        val alertLevel: String,
    ) : java.io.Serializable

    val all: List<SceneMeta> = listOf(
        SceneMeta("pre_shift_briefing", "班前安全交底", "人脸签到 · 智能录制 · 自动纪要", "开启班前安全演讲录制"),
        SceneMeta("post_shift_handover", "班后班组交接", "隐患梳理 · 交接台账 · 责任留痕", "启动班后交接记录"),
        SceneMeta("construction_plan", "施工专项计划", "RAG合规 · JGJ59规范", "生成明日专项施工计划"),
        SceneMeta("construction_log", "电子化施工日志", "全天归集 · 一键成文", "生成今日施工现场工作日志"),
        SceneMeta("device_inspection", "点位设备状态检测", "GPS确权 · 多机编号 · 语音播报", "本机点位设备状态检测"),
        SceneMeta("quality_inspection", "工程质量巡检", "视觉+蓝牙实测 · 整改工单", "发起质检核验"),
        SceneMeta("hazard_supervision", "隐患识别与旁站监督", "声光告警 · 自动取证", "开启旁站施工合规监督"),
        SceneMeta("expert_call", "全双工专家连线", "打断式对讲 · 画面标注", "呼叫技术专家"),
        SceneMeta("sos_emergency", "SOS全域应急联动", "推流 · 爆闪 · 北斗定位", "SOS紧急求助"),
    )

    fun matchFromText(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val rules = listOf(
            "sos_emergency" to listOf("sos", "紧急求助", "一键应急", "全域应急"),
            "expert_call" to listOf("专家", "连线", "远程指导", "呼叫技术", "全双工"),
            "device_inspection" to listOf("设备状态", "状态检测", "点位设备", "本机点位"),
            "pre_shift_briefing" to listOf("班前", "安全交底", "安全演讲", "交底录制"),
            "post_shift_handover" to listOf("班后", "交接记录", "班组交接"),
            "construction_plan" to listOf("施工计划", "专项计划", "明日施工"),
            "construction_log" to listOf("施工日志", "工作日志", "电子日志"),
            "quality_inspection" to listOf("质检", "平整度", "质量核验", "蓝牙测量"),
            "hazard_supervision" to listOf("隐患", "违规", "旁站", "未戴安全帽", "动火"),
        )
        for ((id, kws) in rules) {
            if (kws.any { t.contains(it, ignoreCase = true) }) return id
        }
        return null
    }

    fun run(scenarioId: String, deviceId: String): SceneResult {
        val dev = deviceId.ifBlank { "DEMO-DEVICE" }
        return when (scenarioId) {
            "pre_shift_briefing" -> SceneResult(
                scenarioId, "班前安全交底",
                "班前交底录制已开启。人脸签到完成，应到12人实到11人，缺席已标记。夜视已自动适配。",
                "已开启班前安全演讲录制，完成端侧人脸签到。\n应到12人、实到11人，缺席 XC008 已标记。\n将自动生成《班前安全交底纪要》并上传管控平台。",
                "《班前安全交底纪要》\n设备：$dev\n应到12人/实到11人\n人脸签到11人次通过\n附件：音视频+花名册+纪要\n已同步管控平台",
                listOf("端侧人脸签到", "AI光线自适应", "MDM防干扰", "纪要自动归档"),
                "已同步：管控平台 · 班组 APP",
                listOf(0x01), "info",
            )
            "post_shift_handover" -> SceneResult(
                scenarioId, "班后班组交接",
                "班后交接记录已启动。已梳理隐患3项、进度2项、待整改1项，正在生成交接台账。",
                "已启动班后录音与画面抓拍，AI已整理隐患与进度，生成《班组班后交接台账》并同步平台。",
                "《班组班后交接台账》\n设备：$dev\n隐患：临边护栏松动1处\n进度：基坑支护85%\n待整改：夜视故障已派单",
                listOf("音视频记录", "AI内容梳理", "语音确认", "平台同步"),
                "已同步：管控平台 · 管理 APP",
                listOf(0x01), "info",
            )
            "construction_plan" -> SceneResult(
                scenarioId, "施工专项计划",
                "已结合今日隐患生成明日专项施工计划，语音回执确认后已下发班组及管理人员APP。",
                "依托赢筑RAG知识库（JGJ59）编制专项计划，含点位、工期、安全注意事项与防护要求。",
                "《明日专项施工计划》\n基坑临边/脚手架/临电巡检\n雨天防滑·交叉作业警戒\n已下发班组APP+管控大屏",
                listOf("口语PTT驱动", "JGJ59合规", "语音回执", "多端下发"),
                "已同步：班组 APP · 管控大屏",
                emptyList(), "info",
            )
            "construction_log" -> SceneResult(
                scenarioId, "电子化施工日志",
                "今日数据归集完成，已一键生成合规施工现场工作日志。",
                "已归集巡检画面、语音口述、定位与出勤，生成《施工现场工作日志》并平台归档。",
                "《施工现场工作日志》\n设备：$dev\n画面28张·语音6段·定位4次\n出勤11/12·附实拍佐证",
                listOf("无感归集", "一键成文", "实拍溯源", "语音可修订"),
                "已同步：管控平台档案库",
                emptyList(), "info",
            )
            "device_inspection" -> SceneResult(
                scenarioId, "点位设备状态检测",
                "当前东门基坑点位，本机 $dev 运行全部正常。同点位 CAM-02 运行正常。",
                "GPS点位确权完成。本机电量78%、信号优、存储充足、夜视与录像模块正常。",
                "《点位设备检测报告》\n本机 $dev：电量78%·信号优·TF42GB·夜视正常\n同点位 CAM-02：在线",
                listOf("GPS确权", "多机编号", "语音播报", "运维派单"),
                "已同步：管控平台 · 运维 APP",
                listOf(0x03), "info",
            )
            "quality_inspection" -> SceneResult(
                scenarioId, "工程质量巡检",
                "平整度蓝牙实测6.2毫米，超出JGJ标准，判定不合格。整改工单已推送。",
                "VL视觉+蓝牙实测+知识库联动研判：平整度6.2mm超标，已自动生成质检整改工单。",
                "《质检整改工单》\n平整度实测6.2mm（标准≤4mm）\n附件：照片+蓝牙原始数据",
                listOf("蓝牙实测", "JGJ对标", "自动工单", "复测闭环"),
                "已同步：管控平台 · 整改派单",
                listOf(0x03), "warn",
            )
            "hazard_supervision" -> SceneResult(
                scenarioId, "隐患识别与旁站监督",
                "警告！检测到脚手架违规攀爬、未戴安全帽。请立即停止作业！已抓拍取证并上报。",
                "旁站监督识别违规攀爬与未戴安全帽，已声光告警（<1s），违规取证已推送平台。",
                "《旁站违规取证台账》\n违规：攀爬脚手架+未戴安全帽\n抓拍3张·视频15s·定位已绑定",
                listOf("实时识别", "声光告警", "自动取证", "平台上报"),
                "已同步：平台告警 · 手机推送",
                listOf(0x03), "critical",
            )
            "expert_call" -> SceneResult(
                scenarioId, "全双工专家连线",
                "专家连线已建立。全双工通道就绪，专家可实时标注画面并插话指导。",
                "已发起赢筑全双工专家连线，第一视角画面已推送，支持多方协同会商与留痕归档。",
                "《专家连线存档》\n全双工音视频+专家标注\n参会：安全员·专家·监理",
                listOf("全双工对讲", "第一视角", "画面标注", "留痕归档"),
                "已同步：管控平台 · 专家台",
                listOf(0x04), "info",
            )
            "sos_emergency" -> SceneResult(
                scenarioId, "SOS全域应急联动",
                "SOS紧急告警已触发！推流与爆闪已开启，北斗定位已上传，全员已收到告警。",
                "SOS已触发：全高清推流、红蓝爆闪、北斗定位上传，平台与全员APP联动调度中。",
                "《SOS紧急告警》\n设备：$dev\n推流+爆闪+北斗定位\n平台弹窗+全员APP通知",
                listOf("SOS按键", "推流爆闪定位", "全员告警", "就近调度"),
                "已同步：应急指挥平台",
                listOf(0x01), "critical",
            )
            else -> SceneResult(
                "", "未知场景", "", "未找到演示场景", "", emptyList(), "", emptyList(), "info",
            )
        }
    }
}
