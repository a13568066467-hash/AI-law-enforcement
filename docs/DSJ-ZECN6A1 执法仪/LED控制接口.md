# DSJ-ZECN6A1 / ZE69 LED 控制接口

> 对照厂商《执法仪接口文档.docx》LED 章节与同包 `ZE69-驱动控制接口.txt`，按 **真机验证**（`Ze69SysfsPaths` / `Ze69Hardware` / `DeviceStatusIndicator`）整理。  
> 基路径（正确）：`/sys/devices/platform/odm/odm:camera_als`

写入需节点可写（指示灯多为 `777`；部分节点仍可能要系统签名）。Debug 包写失败时功能可用，仅机身灯不亮。

---

## 1. 正确接口（以真机为准）

| 功能 | 节点（相对基路径） | 取值 | 说明 |
|------|-------------------|------|------|
| 红色指示灯 | `indicator_red_led` | `0` / `1` | 开关量，无 0–6 亮度档 |
| 绿色指示灯 | `indicator_green_led` | `0` / `1` | 同上 |
| 白光 / 镭射灯 | `radium_spotlight` | `0` / `1` | PTT 短按白光 |
| 红外补光 | `ir_led` | `0`～`255` | PWM；`0` 关 |
| 红外滤光片 IR-CUT | `ir_door` | `0` / `1` | 与 `ir_led` 配合夜视 |
| 光感（只读） | `als_data` | 数字字符串 | 可带换行；无单位 |

**本机不存在 / 勿依赖**

| 文档或旧名 | 结论 |
|------------|------|
| `indicator_blue_led` | 无独立蓝灯节点 |
| `rgb_red_led` / `rgb_green_led` / `rgb_blue_led` | 旧名；真机指示灯用 `indicator_*` |
| `rg_red_led` / `rg_green_led` | 旧名；映射到 `indicator_*` |
| `leise_led` | 文档镭射旧名；白光请用 `radium_spotlight` |
| `/sys/class/leds/{red,green,blue}/brightness` | 非本机状态灯路径 |
| `ir_red_led` / `ir_blue_led` | 文档「警示灯」名错误 |
| `als_power` 当红外补光 | 红外请用 `ir_led` + `ir_door` |

完整路径示例：

```text
echo 1 > /sys/devices/platform/odm/odm:camera_als/indicator_green_led
echo 0 > /sys/devices/platform/odm/odm:camera_als/indicator_red_led
echo 1 > /sys/devices/platform/odm/odm:camera_als/radium_spotlight
echo 0 > /sys/devices/platform/odm/odm:camera_als/ir_led
echo 0 > /sys/devices/platform/odm/odm:camera_als/ir_door
cat /sys/devices/platform/odm/odm:camera_als/als_data
```

代码入口：`Ze69Hardware.setIndicatorRed/Green`、`setSpotlight` / `setWhiteLight`、`setIrBrightness`、`setIrCut`、`readAlsData`。

---

## 2. 业务状态灯映射（App）

说明书语义 → 红/绿指示灯组合（无硬件黄灯/蓝灯时用红+绿近似黄）：

| 业务状态 | 灯效 | sysfs |
|----------|------|--------|
| 待机 | 绿灯常亮 | `indicator_green=1`, `indicator_red=0` |
| 录像 | 红灯闪 | `indicator_red` 闪，绿灭 |
| 录音 | 「黄灯」闪 | 红+绿同闪 |
| 拍照 | 红灯闪一次 | `pulsePhotoCapture` |
| 充电 | 红灯常亮 | 红亮绿灭 |
| 充满 | 绿灯常亮 | 同待机 |
| 视频连线推流 | 红绿交替 | 红绿反相闪 |
| 白光灯 | 镭射/白光 | `radium_spotlight=1` |
| 红外夜视 | **产品默认关闭** | 强制 `ir_led=0` + `ir_door=0` |
| AI 聆听 | 无蓝灯；现用红灯提示 | `indicator_red`（勿写不存在的蓝灯节点） |

实现：`DeviceStatusIndicator`。

---

## 3. 《执法仪接口文档.docx》LED 段错误清单

以下均出自适配包内 `执法仪接口文档.docx` 开篇「LED控制」表，**不要按原文直接写 sysfs**。

| # | 文档原文 | 问题 | 正确做法 |
|---|----------|------|----------|
| 1 | 「所有灯亮度一律默认最大（6）」 | 指示灯 / 镭射为 **0/1**，不是 0–6 | 开关灯写 `0`/`1`；仅 `ir_led` 用 0–255 |
| 2 | `/sys/class/leds/red|green|blue/brightness`，有效值 0–6 | 路径与取值均不符合本机状态灯 | 用 `…/camera_als/indicator_red_led`、`indicator_green_led` |
| 3 | 蓝色 LED 备注写「打开**黄色** LED」 | 文案笔误（蓝写成黄） | 且本机无 class blue；黄灯用红+绿同亮近似 |
| 4 | 红色/蓝色警示灯：`ir_red_led`、`ir_blue_led`，0–6 | 节点名错误；亦非 0–6 档 | 状态警示用 `indicator_red_led` / `indicator_green_led` |
| 5 | Camera 红外补光 = `als_power`（1/on/en） | 补光节点错误 | 补光 `ir_led`；滤光片 `ir_door`；`als_data` 只读光感 |
| 6 | 三色指示灯路径：`/sys/bus/platform/drivers/camera-als/odm:camera_als/indicator_*` | **drivers** 路径不可靠 | 统一用 `/sys/devices/platform/odm/odm:camera_als/…` |
| 7 | 列出 `indicator_blue_led` | 真机无独立蓝灯 | 不要写该节点；需要「蓝」时改业务灯效或忽略 |
| 8 | `ir_led`：打开写 `1`，又写 PWM 1–255 | 表述打架 | 关=`0`；开=亮度 `1`–`255` |
| 9 | 镭射灯 `radium_spotlight` 写 0/1 | 此项路径与取值正确 | 可直接用 |

---

## 4. 同包 `ZE69-驱动控制接口.txt` 错误清单

| # | 文档原文 | 问题 | 正确做法 |
|---|----------|------|----------|
| 1 | `rgb_blue_led` 一行标注为「**红灯**」 | 标签笔误（应为蓝灯）；且节点本身过时 | 指示用 `indicator_*`；无蓝灯硬件 |
| 2 | 三色灯 `rgb_*`、红绿灯 `rg_*` | 真机状态灯为 `indicator_*` | 见 §1 |
| 3 | 镭射 `leise_led` | 旧名 | 用 `radium_spotlight` |
| 4 | 基路径下节点名与现 ROM 不一致 | 易联调失败 | 以 §1 与 `Ze69SysfsPaths.kt` 为准 |

仓库内 `docs/hardware/ZE69-驱动控制接口.txt` 曾按该文件摘录，**节点名已过时**；LED 细节以本文为准。

---

## 5. 与 HTTP 云端 API 的关系

LED / 灯控是 **设备本地 sysfs**，不是 `backend` HTTP 接口。  
云端 `/v1/chat` 可返回 `ble_cmds` 等意图，由 App 再调 `Ze69Hardware`；勿把本文节点当成 REST 路径。

云端 HTTP 总表见 [API接口.md](../guides/API接口.md)。

---

## 6. 相关代码与文档

| 路径 | 说明 |
|------|------|
| `android-app/.../Ze69SysfsPaths.kt` | 节点常量 |
| `android-app/.../Ze69Hardware.kt` | 写入封装与真机注释 |
| `android-app/.../DeviceStatusIndicator.kt` | 业务灯效 |
| [通信协议规范.md](../architecture/通信协议规范.md) §硬件灯控 | 协议侧节点表 |
| [交互设计.md](../product/交互设计.md) | 按键与灯效产品语义 |
