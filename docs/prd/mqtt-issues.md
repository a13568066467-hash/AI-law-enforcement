# Issues: MQTT 信令通道

> 来源 PRD: `docs/prd/mqtt-signaling-channel.md`

---

## Issue 1: `MqttConfig` — 连接配置与凭据管理

**模块**: `data/MqttConfig.kt` (新增)

**范围**:
- 定义 `productKey` / `deviceName` / `deviceSecret` / `brokerUri` 字段
- SharedPreferences 持久化
- 首次从云端 API 获取凭据（`POST /auth/mqtt/credentials`）
- 支持 `enabled` 开关，默认 `false`（不影响未配好 IoT 平台时的现有功能）

**验收**:
- `enabled = false` 时 `MqttClient` 完全不做任何初始化
- 凭据持久化后重启 App 仍可用

---

## Issue 2: `MqttClient` — 连接生命周期 & 自动重连

**模块**: `platform/MqttClient.kt` (新增)
**依赖**: Issue 1

**范围**:
- 封装 Eclipse Paho `MqttAndroidClient`
- `connect(onConnected, onDisconnected)` — 建立 TLS 连接
- `disconnect()` — 优雅断连
- `publish(topic, payload, qos)` — 发布消息
- `subscribe(topic, qos, callback)` — 订阅主题
- 自动重连：指数退避 5s → 15s → 45s → cap 60s
- keepalive 60s
- `isConnected()` 状态查询
- 日志：连接成功/断开/重连均输出 INFO

**验收**:
- 连上 broker 后 `onConnected` 回调触发
- 手动 kill broker → `onDisconnected` 触发 → 5s 后自动重连
- 重连成功后日志可见 "MQTT reconnected"

---

## Issue 3: `MqttTopicRouter` — 云端指令分发

**模块**: `platform/MqttTopicRouter.kt` (新增)
**依赖**: Issue 2

**范围**:
- 订阅 3 个 topic：
  - `/service/record` → `SessionManager.dispatchRemoteCmd()`
  - `/service/scene` → `SessionManager.runDemoScenario()`
  - `/service/broadcast` → `SessionManager.handleBroadcast()`
- 未知 topic 记录 WARN 日志
- 所有 topic 使用 QoS 1

**验收**:
- 云端 publish 到 `/service/record` `{action:"start"}` → 设备开始录像
- 未知 topic 不崩溃，日志可见 WARN

---

## Issue 4: `MqttHeartbeat` — 定时状态上报

**模块**: `platform/MqttHeartbeat.kt` (新增)
**依赖**: Issue 2

**范围**:
- `MqttClient` 连接成功后启动 60s 定时器
- 每 60s publish 到 `/event/heartbeat/post`
- JSON payload 包含：
  - `batteryPct`, `isCharging`
  - `storageFreeMb`, `totalStorageMb`
  - `isRecording`, `isAudioRecording`
  - `gpsLat`, `gpsLng`（若可用）
  - `signalStrength`（4G 信号强度）
- 断连时自动停止定时器，重连后恢复

**验收**:
- 连上 broker 后 `/event/heartbeat/post` 每 60s 收到一条消息
- 断连后停止发送，重连后恢复

---

## Issue 5: `SessionManager` 扩展 — 远程指令 & 事件发布

**模块**: `data/SessionManager.kt` (修改)
**依赖**: Issue 3、Issue 4

**范围**:

```kotlin
// 新增方法
fun onMqttConnected()              // 连接成功 → 发布完整状态
fun dispatchRemoteCmd(json: JSONObject) // 统一远程指令入口
fun handleBroadcast(json: JSONObject)   // TTS 播报群发通知
fun publishSosEvent()               // F3 长按后追加
fun publishRecordEvent(action: String)  // 录像开始/停止后追加
fun publishMediaEvent(file: File)   // 媒体保存后追加
```

- `dispatchRemoteCmd` 解析 `action` 字段：
  - `"start"` → `startRecord()`
  - `"stop"` → `stopRecord()`
  - `"capture"` → `triggerCapture()`
- `handleBroadcast`：解析 `{title, body, level}`，调用 `TtsSpeaker.speak(body)`
- `publishSosEvent`：在现有 `runDemoScenario("sos_emergency")` 后追加 MQTT 发布（QoS 2）
- `publishMediaEvent`：在 `onNativeRecordStopped` / `onPhonePhotoCaptured` 后追加

**验收**:
- 远程指令各 action 正确执行
- SOS 后云端 2s 内收到事件
- 现有 31 个单元测试全部通过

---

## Issue 6: `build.gradle.kts` — 引入 Paho 依赖

**模块**: `app/build.gradle.kts` (修改)
**依赖**: 无

**范围**:
```kotlin
implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
```

**验收**:
- `./gradlew assembleDebug` 编译通过
- 新增依赖 < 300KB

---

## Issue 7: 端到端集成验证

**依赖**: Issue 1–6 全部完成

**验证项**:
1. 设备上电 → MQTT 自动连接 → 日志确认
2. 指挥中心 Web 下发 `start_record` → 设备 3s 内开始录像
3. 设备 F3 长按 → 云端收到 SOS 事件
4. 断网 30s 后恢复 → 自动重连，收到离线指令
5. 群发通知 → 设备 TTS 播报
6. `MqttConfig.enabled = false` → MQTT 模块完全不初始化
7. 31 个单元测试通过
8. HTTP 所有接口（登录/对话/识图）正常工作
