from __future__ import annotations

import re
from datetime import datetime, timezone
from typing import Any

SCENARIOS: dict[str, dict[str, str]] = {
    "pre_shift_briefing": {
        "title": "班前安全交底",
        "subtitle": "人脸签到 · 智能录制 · 自动纪要",
        "ptt_hint": "开启班前安全演讲录制",
    },
    "post_shift_handover": {
        "title": "班后班组交接",
        "subtitle": "隐患梳理 · 交接台账 · 责任留痕",
        "ptt_hint": "启动班后交接记录",
    },
    "construction_plan": {
        "title": "施工专项计划",
        "subtitle": "RAG合规知识库 · JGJ59规范",
        "ptt_hint": "结合今日隐患生成明日专项施工计划",
    },
    "construction_log": {
        "title": "电子化施工日志",
        "subtitle": "全天数据归集 · 一键成文",
        "ptt_hint": "生成今日施工现场工作日志",
    },
    "device_inspection": {
        "title": "点位设备状态检测",
        "subtitle": "GPS确权 · 多机编号 · 语音播报",
        "ptt_hint": "本机点位设备状态检测",
    },
    "quality_inspection": {
        "title": "工程质量巡检",
        "subtitle": "视觉+蓝牙实测 · 整改工单",
        "ptt_hint": "发起质检核验",
    },
    "hazard_supervision": {
        "title": "隐患识别与旁站监督",
        "subtitle": "声光告警 · 自动取证 · 平台上报",
        "ptt_hint": "开启旁站施工合规监督",
    },
    "expert_call": {
        "title": "全双工专家连线",
        "subtitle": "打断式对讲 · 画面标注 · 远程指导",
        "ptt_hint": "呼叫技术专家",
    },
    "sos_emergency": {
        "title": "SOS全域应急联动",
        "subtitle": "推流 · 爆闪 · 北斗定位 · 全员告警",
        "ptt_hint": "SOS紧急求助",
    },
}

_KEYWORD_RULES: list[tuple[str, list[str]]] = [
    ("sos_emergency", ["sos", "紧急求助", "一键应急", "全域应急"]),
    ("expert_call", ["专家", "连线", "远程指导", "呼叫技术", "全双工"]),
    ("device_inspection", ["设备状态", "状态检测", "点位设备", "本机点位", "设备自检"]),
    ("pre_shift_briefing", ["班前", "安全交底", "安全演讲", "交底录制", "人脸签到"]),
    ("post_shift_handover", ["班后", "交接记录", "班组交接", "班后交接"]),
    ("construction_plan", ["施工计划", "专项计划", "专项施工", "明日施工", "排班计划"]),
    ("construction_log", ["施工日志", "工作日志", "电子日志", "日志生成"]),
    ("quality_inspection", ["质检", "平整度", "质量核验", "实测实量", "蓝牙测量"]),
    ("hazard_supervision", ["隐患", "违规", "旁站", "监督巡检", "未戴安全帽", "动火"]),
]


def list_scenarios() -> list[dict[str, str]]:
    return [
        {"id": sid, **meta}
        for sid, meta in SCENARIOS.items()
    ]


def match_scenario(text: str) -> str | None:
    t = text.strip().lower()
    if not t:
        return None
    for sid, keywords in _KEYWORD_RULES:
        for kw in keywords:
            if kw.lower() in t:
                return sid
    return None


def _now_label() -> str:
    return datetime.now(timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M")


def _run_pre_shift_briefing(device_id: str) -> dict[str, Any]:
    doc = (
        f"《班前安全交底纪要》\n"
        f"时间：{_now_label()}\n"
        f"点位：东门基坑作业区（GPS确权）\n"
        f"设备：{device_id}\n"
        f"应到 12 人 / 实到 11 人（缺席：XC008 王六，已标记）\n"
        f"人脸签到：11 人次二次比对通过，无代签到\n"
        f"交底要点：临边防护、动火审批、脚手架验收状态复核\n"
        f"附件：交底音视频 + 签到花名册 + 电子纪要\n"
        f"状态：已同步管控平台及班组手机 APP"
    )
    return {
        "scenario_id": "pre_shift_briefing",
        "title": SCENARIOS["pre_shift_briefing"]["title"],
        "voice_broadcast": (
            "班前交底录制已开启。AI人脸签到完成，应到12人实到11人，"
            "缺席人员已标记。红外夜视已根据现场亮度自动适配。"
            "MDM已锁定状态栏，防止误操作中断录制。"
        ),
        "reply": (
            "已开启班前安全演讲高清录制，并完成端侧人脸签到核验。\n"
            "应到12人、实到11人，缺席 XC008 已标记。\n"
            "录制结束后将自动生成《班前安全交底纪要》并上传管控平台。"
        ),
        "document": doc,
        "highlights": [
            "端侧人脸签到二次比对",
            "AI光线自适应夜视",
            "MDM防干扰锁定",
            "纪要自动归档上传",
        ],
        "platform_sync": "已同步：管控平台 · 班组 APP",
        "ble_cmds": [{"cmd": 0x01}],
        "alert_level": "info",
    }


def _run_post_shift_handover(device_id: str) -> dict[str, Any]:
    doc = (
        f"《班组班后交接台账》\n"
        f"时间：{_now_label()}\n"
        f"设备：{device_id}\n"
        f"当日隐患：临边护栏松动 1 处（已拍照）\n"
        f"施工进度：基坑支护完成 85%\n"
        f"待整改：东门摄像头夜视故障（已派单）\n"
        f"交接确认：安全员语音确认留痕\n"
        f"状态：音视频+台账已同步平台"
    )
    return {
        "scenario_id": "post_shift_handover",
        "title": SCENARIOS["post_shift_handover"]["title"],
        "voice_broadcast": (
            "班后交接记录已启动。已梳理当日隐患3项、进度节点2项、待整改1项。"
            "正在生成标准化交接台账，请双方语音确认。"
        ),
        "reply": (
            "已启动班后高清录音与重点画面抓拍。\n"
            "AI已整理隐患、进度与待整改项，正在生成《班组班后交接台账》。\n"
            "交接音视频与电子台账将同步管控平台，管理人员可远程查阅。"
        ),
        "document": doc,
        "highlights": ["全程音视频记录", "AI口述内容梳理", "双方语音确认", "平台双向同步"],
        "platform_sync": "已同步：管控平台 · 管理人员 APP",
        "ble_cmds": [{"cmd": 0x01}],
        "alert_level": "info",
    }


def _run_construction_plan(device_id: str) -> dict[str, Any]:
    doc = (
        f"《明日专项施工计划》\n"
        f"编制：赢筑施工 RAG 知识库（JGJ59）\n"
        f"设备：{device_id}\n"
        f"作业点位：基坑临边 / 脚手架 / 临电箱巡检\n"
        f"工期：明日 08:00–17:30\n"
        f"安全注意事项：雨天防滑、交叉作业警戒、临边双道护栏\n"
        f"防护要求：安全带100%佩戴、动火票复核\n"
        f"特殊工况：夜间交叉作业，已优化排班与风险防控条款\n"
        f"下发：班组 APP + 管控平台大屏（本机仅语音回执）"
    )
    return {
        "scenario_id": "construction_plan",
        "title": SCENARIOS["construction_plan"]["title"],
        "voice_broadcast": (
            "已结合今日基坑、脚手架、临边隐患，生成明日专项施工计划。"
            "计划已通过本机语音回执确认，已下发班组及管理人员手机APP。"
        ),
        "reply": (
            "已依托赢筑施工RAG合规知识库，贴合JGJ59规范编制专项计划。\n"
            "包含点位、工期、安全注意事项与防护要求全套方案。\n"
            "计划已同步班组及管理人员手机APP与管控平台，适配小屏短板。"
        ),
        "document": doc,
        "highlights": ["口语化PTT下达", "JGJ59 RAG合规", "语音回执确认", "多端同步下发"],
        "platform_sync": "已同步：班组 APP · 管控平台大屏",
        "ble_cmds": [],
        "alert_level": "info",
    }


def _run_construction_log(device_id: str) -> dict[str, Any]:
    doc = (
        f"《施工现场工作日志》\n"
        f"日期：{_now_label()[:10]}\n"
        f"设备：{device_id}\n"
        f"归集：巡检画面 28 张 · 语音作业记录 6 段 · 打卡定位 4 次\n"
        f"天气：多云转小雨 · 出勤 11/12\n"
        f"隐患闭环：临边护栏整改完成 · 夜视故障处理中\n"
        f"佐证：现场实拍图 + 语音溯源\n"
        f"备注：可通过语音随时增补临时施工与整改说明"
    )
    return {
        "scenario_id": "construction_log",
        "title": SCENARIOS["construction_log"]["title"],
        "voice_broadcast": (
            "今日作业数据归集完成。已整合巡检画面、语音口述、定位与出勤数据，"
            "一键生成合规施工现场工作日志，无需手写填报。"
        ),
        "reply": (
            "AI已自动归集全天巡检画面、语音作业内容、打卡定位与天气工况。\n"
            "已生成标准化《施工现场工作日志》，附带现场实拍佐证。\n"
            "支持语音修改与增补备注，平台统一归档可随时调阅。"
        ),
        "document": doc,
        "highlights": ["全程无感归集", "一键合规成文", "实拍佐证溯源", "语音可修订"],
        "platform_sync": "已同步：管控平台档案库",
        "ble_cmds": [],
        "alert_level": "info",
    }


def _run_device_inspection(device_id: str) -> dict[str, Any]:
    voice_ok = (
        f"当前东门基坑作业点位，本机设备 {device_id} 运行全部正常。"
        f"同点位编号 CAM-02 摄像设备运行正常。"
    )
    doc = (
        f"《点位设备状态检测报告》\n"
        f"时间：{_now_label()}\n"
        f"GPS点位：东门基坑作业区（确权锁定）\n"
        f"本机 {device_id}：电量 78% · 4G信号优 · TF剩余 42GB · 夜视正常 · 北斗定位正常 · MDM在线\n"
        f"同点位 CAM-02：在线 · 存储正常\n"
        f"播报：<1s 响应 · 0遗漏\n"
        f"状态：报告已归档，运维可按点位定向检修"
    )
    return {
        "scenario_id": "device_inspection",
        "title": SCENARIOS["device_inspection"]["title"],
        "voice_broadcast": voice_ok,
        "reply": (
            "赢筑AI已联动北斗GPS与现场实拍画面完成点位确权。\n"
            f"本机 {device_id}：电量78%、信号优、存储充足、夜视与录像模块正常。\n"
            "同点位多设备已按编号独立核验，报告已同步管控平台。"
        ),
        "document": doc,
        "highlights": ["GPS点位确权", "多设备编号区分", "AI语音播报", "运维定向派单"],
        "platform_sync": "已同步：管控平台 · 运维 APP",
        "ble_cmds": [{"cmd": 0x03}],
        "alert_level": "info",
    }


def _run_quality_inspection(device_id: str) -> dict[str, Any]:
    doc = (
        f"《工程质量质检整改工单》\n"
        f"时间：{_now_label()}\n"
        f"设备：{device_id}\n"
        f"核验项：墙面平整度（蓝牙平整度仪实测）\n"
        f"标准：JGJ施工验收标准 ≤4mm\n"
        f"实测：6.2mm（超标）\n"
        f"附件：现场核验照片 + 蓝牙原始测量数据\n"
        f"整改工艺：重新找平 · 复测后归档\n"
        f"状态：已推送管理人员手机APP"
    )
    return {
        "scenario_id": "quality_inspection",
        "title": SCENARIOS["quality_inspection"]["title"],
        "voice_broadcast": (
            "质检核验完成。墙面平整度蓝牙实测6.2毫米，超出JGJ标准，判定不合格。"
            "已自动留存照片与测量数据，整改工单已推送平台。"
        ),
        "reply": (
            "赢筑VL视觉+蓝牙实测数据+施工合规知识库三方联动研判完成。\n"
            "平整度实测6.2mm，超出JGJ标准（≤4mm），已判定不合格。\n"
            "现场照片与蓝牙原始数据已打包上传，自动生成质检整改工单。"
        ),
        "document": doc,
        "highlights": ["蓝牙硬件实测", "JGJ标准对标", "不合格自动工单", "复测闭环归档"],
        "platform_sync": "已同步：管控平台 · 整改派单",
        "ble_cmds": [{"cmd": 0x03}],
        "alert_level": "warn",
    }


def _run_hazard_supervision(device_id: str) -> dict[str, Any]:
    doc = (
        f"《旁站违规取证台账》\n"
        f"时间：{_now_label()}\n"
        f"设备：{device_id}\n"
        f"违规类型：脚手架违规攀爬 + 未佩戴安全帽\n"
        f"处置：本地语音播报 + 红蓝爆闪提醒（已触发）\n"
        f"取证：抓拍画面 3 张 · 短视频 15s · 北斗定位\n"
        f"上报：高危违规已推送管控平台\n"
        f"响应：<1s 声光告警"
    )
    return {
        "scenario_id": "hazard_supervision",
        "title": SCENARIOS["hazard_supervision"]["title"],
        "voice_broadcast": (
            "警告！检测到脚手架违规攀爬，作业人员未佩戴安全帽。"
            "请立即停止违规作业！已自动抓拍取证并上报平台。"
        ),
        "reply": (
            "AI旁站监督识别到违规攀爬脚手架、未戴安全帽。\n"
            "已触发语音播报+红蓝爆闪双重提醒（<1s响应）。\n"
            "违规画面、短视频与定位已自动归档，高危告警已推送管控平台。"
        ),
        "document": doc,
        "highlights": ["端侧视觉实时识别", "声光就地告警", "自动取证留痕", "平台一键上报"],
        "platform_sync": "已同步：管控平台告警 · 手机APP推送",
        "ble_cmds": [{"cmd": 0x03}],
        "alert_level": "critical",
    }


def _run_expert_call(device_id: str) -> dict[str, Any]:
    doc = (
        f"《专家连线指导存档》\n"
        f"时间：{_now_label()}\n"
        f"设备：{device_id}\n"
        f"模式：赢筑全双工打断式音视频\n"
        f"参会：现场安全员 · 技术专家 · 项目监理\n"
        f"内容：异形构件返修工艺 · 现场风险区域标注\n"
        f"留痕：双向对讲音视频 + 专家画面标注\n"
        f"状态：已同步管控平台及管理人员APP"
    )
    return {
        "scenario_id": "expert_call",
        "title": SCENARIOS["expert_call"]["title"],
        "voice_broadcast": (
            "专家连线已建立。全双工通道就绪，专家可实时查看第一视角画面并插话指导。"
            "支持画面标注与多方协同会商。"
        ),
        "reply": (
            "已发起赢筑全双工专家连线，第一视角现场画面已推送。\n"
            "专家可远程标注整改区域、讲解施工工艺、研判现场风险。\n"
            "双向对讲音视频与标注画面将自动存档并同步平台。"
        ),
        "document": doc,
        "highlights": ["全双工打断对讲", "第一视角推流", "画面标注指导", "全程留痕归档"],
        "platform_sync": "已同步：管控平台 · 专家调度台",
        "ble_cmds": [{"cmd": 0x04}],
        "alert_level": "info",
    }


def _run_sos_emergency(device_id: str) -> dict[str, Any]:
    doc = (
        f"《SOS紧急告警记录》\n"
        f"时间：{_now_label()}\n"
        f"设备：{device_id}\n"
        f"动作：全高清推流开启 · 红蓝爆闪全开 · 北斗精准定位上传\n"
        f"坐标：31.2304°N 121.4737°E（示例）\n"
        f"通知：管控平台弹窗 + 全员手机APP + 就近安全员调度\n"
        f"状态：应急处置链路已激活"
    )
    return {
        "scenario_id": "sos_emergency",
        "title": SCENARIOS["sos_emergency"]["title"],
        "voice_broadcast": (
            "SOS紧急告警已触发！全高清推流与爆闪警示已开启，"
            "北斗精准定位已上传，平台与全员手机已收到告警。"
        ),
        "reply": (
            "SOS一键应急已触发：全高清推流、红蓝爆闪警示灯、北斗精准定位上传。\n"
            "紧急告警已推送管控平台与全员手机APP，就近安全员联动调度中。"
        ),
        "document": doc,
        "highlights": ["SOS物理按键", "推流+爆闪+定位", "平台全员告警", "就近联动调度"],
        "platform_sync": "已同步：应急指挥平台 · 全员APP",
        "ble_cmds": [{"cmd": 0x01}],
        "alert_level": "critical",
    }


_RUNNERS = {
    "pre_shift_briefing": _run_pre_shift_briefing,
    "post_shift_handover": _run_post_shift_handover,
    "construction_plan": _run_construction_plan,
    "construction_log": _run_construction_log,
    "device_inspection": _run_device_inspection,
    "quality_inspection": _run_quality_inspection,
    "hazard_supervision": _run_hazard_supervision,
    "expert_call": _run_expert_call,
    "sos_emergency": _run_sos_emergency,
}


def run_scenario(scenario_id: str, *, device_id: str = "DEMO-DEVICE") -> dict[str, Any]:
    runner = _RUNNERS.get(scenario_id)
    if runner is None:
        return {
            "scenario_id": "",
            "title": "未知场景",
            "reply": "未找到对应演示场景",
            "voice_broadcast": "",
            "document": "",
            "highlights": [],
            "platform_sync": "",
            "ble_cmds": [],
            "alert_level": "info",
        }
    return runner(device_id.strip() or "DEMO-DEVICE")


def route_demo_chat(text: str, *, device_id: str = "") -> dict[str, Any] | None:
    """PTT 口语命中场景关键词时返回演示结果，供 agents.route_chat 合并。"""
    sid = match_scenario(text)
    if sid is None:
        return None
    result = run_scenario(sid, device_id=device_id)
    result["agent"] = "A"
    result["intent"] = "demo_scenario"
    return result
