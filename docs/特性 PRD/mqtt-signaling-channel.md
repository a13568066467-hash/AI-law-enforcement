# PRD: 设备 MQTT 信令通道

> **状态: Historical / 部分落地** | 日期: 2026-07-08  
> 设备侧订阅与客户端栈已具备；**指挥连线下行当前以 HTTP poll 为主**（后端 MQTT 发布器仍为 Logging）。  
> Topic/协议细节以 [`通信协议规范.md`](../架构与数据/协议/通信协议规范.md) §1 为准，避免双处维护长文。

## 1. 背景

当前设备与云端通过 **HTTP REST** 通信。请求-响应模式下存在三个核心缺口：

| 缺口 | 现状 | 影响 |
|---|---|---|
| 云端无法主动推送指令 | 只能等设备主动 HTTP 请求 | 指挥中心不能远程启停录像、切换场景 |
| 紧急事件无实时上报 | SOS 仅本地处理 | 指挥中心不知道现场异常 |
| 多设备无法群控 | 无广播/组播机制 | 警情通知需逐台手动通知 |

本设计引入 **MQTT** 作为第二通信通道，与现有 HTTP 并存。

## 2. 目标与非目标

### 2.1 目标

- 指挥中心可实时远程启停录像、拍照、切换场景（≤ 3s 端到端）
- SOS 触发后 2s 内推送到云端
- 指挥中心可同时向一组设备广播通知
- 离线消息缓存：设备断网重连后能收到期间下发的指令
- HTTP 不受影响，保持现有全部功能

### 2.2 非目标

- MQTT 不替代 HTTP（图片/视频仍走 HTTP 上传）
- 不实现 P2P 设备间通信
- 不实现音视频流推流（那是 WebRTC/RTMP 的范畴）

## 3. 架构

```
指挥中心 Web                             Android 设备
   │                                        │
   │  HTTPS / AMQP                          │ MQTT (TLS 1.2, QoS 1)
   ▼                                        ▼
┌──────────────────────────────────────────────────┐
│              云 IoT 平台（Broker）                 │
│  • 设备证书认证         • 规则引擎（路由/存储）     │
│  • 离线消息缓存         • 设备影子（状态同步）       │
└──────────────────────────────────────────────────┘
```

- **设备认证**：IoT 平台签发的设备证书（预埋或首次激活时动态申请）
- **通道加密**：TLS 1.2
- **QoS**：信令 QoS 1（至少一次），SOS QoS 2（精确一次）

## 4. Topic 设计

### 4.1 设备 → 云端

| Topic | QoS | 内容 | 触发时机 |
|---|---|---|---|
| `/s/{pk}/{dn}/event/heartbeat/post` | 1 | 电量/存储/录制状态/GPS | 定时 60s |
| `/s/{pk}/{dn}/event/sos/post` | 2 | SOS类型 + 位置 + 时间戳 | F3 长按触发 |
| `/s/{pk}/{dn}/event/media/post` | 1 | {fileName, size, path} | 录像/拍照保存后 |
| `/s/{pk}/{dn}/event/record/post` | 1 | {action:"started"/"stopped"} | 录像开始/结束时 |

### 4.2 云端 → 设备

| Topic | QoS | 内容 | 设备动作 |
|---|---|---|---|
| `/s/{pk}/{dn}/service/record` | 1 | `{action:"start"/"stop"/"capture"}` | 执行录制/拍照 |
| `/s/{pk}/{dn}/service/scene` | 1 | `{scene_id:"pre_shift_briefing"}` | 切换 AI 场景 |
| `/s/{pk}/{dn}/service/broadcast` | 1 | `{title, body, level}` | TTS 播报 + 通知栏 |

> `{pk}` = productKey（产品标识），`{dn}` = deviceName（设备编号 DSJ-xxx）

## 5. 模块拆解

### M1: `MqttConfig` — 连接配置

- 存储 broker URI / productKey / deviceName / deviceSecret
- 首次从云端 API 获取，缓存到 SharedPreferences
- 支持热更新（云端下发新 broker 地址）

### M2: `MqttClient` — 连接生命周期

- 基于 Eclipse Paho `MqttAndroidClient`
- 自动重连（指数退避 5s → 15s → 45s）
- keepalive 60s
- 连接状态回调：`onConnected` / `onDisconnected`
- 提供 `publish(topic, payload, qos)` 和 `subscribe(topic, qos, callback)`

### M3: `MqttTopicRouter` — 指令分发

- 收到 `/service/record` → `SessionManager.dispatchRemoteCmd()`
- 收到 `/service/scene` → `SessionManager.runDemoScenario()`
- 收到 `/service/broadcast` → `SessionManager.handleBroadcast()`
- 未匹配 topic 记录 WARN 日志

### M4: `MqttHeartbeat` — 定时心跳

- 每 60s 发布当前设备状态快照
- JSON 内容：`{batteryPct, isCharging, storageFreeMb, isRecording, isAudioRecording, gps{lat,lng}, signalStrength}`

### M5: `SessionManager` 扩展

```kotlin
// 新增方法
fun onMqttConnected()               // 上线时上报完整状态
fun dispatchRemoteCmd(cmd: String)   // 统一远程指令入口
fun handleBroadcast(payload: JSONObject) // 群播通知
fun publishSosEvent()                // 在现有 SOS 逻辑后追加
fun publishMediaEvent(file: File)    // 在现有保存逻辑后追加
```

## 6. 不影响现有功能

| 现有模块 | 影响 |
|---|---|
| `NativeRecorder` | 无 |
| `RecorderKeyDispatcher` | 无（仅按键逻辑不变） |
| `PttSnapAskController` | 无 |
| `ApiClient` (HTTP) | 无（所有 HTTP 接口保持不变） |
| `BackendDiscovery` | 无（仅用于 HTTP，MQTT 走自己的 broker） |
| `MediaInteractionPolicy` | 无 |

## 7. 验收标准

- [ ] 设备上电后自动连接 MQTT Broker
- [ ] 指挥中心下发 `start_record` → 设备 3s 内开始录像
- [ ] 设备 F3 长按 SOS → 云端 2s 内收到事件
- [ ] 设备断网 30s 后重连 → 自动恢复，收到离线期间缓存的指令
- [ ] 指挥中心群发通知 → 订阅该 group 的所有设备均 TTS 播报
- [ ] 31 个已有单元测试全部通过
- [ ] 功能开关：`MqttConfig.enabled = false` 时完全不初始化 MQTT 模块

## 8. 风险

| 风险 | 缓释措施 |
|---|---|
| 4G 信号弱导致 MQTT 频繁断连 | 指数退避重连 + keepalive 60s + 断连时本地独立工作 |
| Paho 库与 Android 14+ 兼容性 | 验证 `targetSdk 35` 编译通过 |
| IoT 平台选型未定 | `MqttClient` 封装通用接口，更换平台只需改配置 |
| MQTT 连接增加耗电 | keepalive 60s（非 10s），仅心跳无大数据传输 |
