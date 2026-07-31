# 执法仪：业务 DeviceBus WebSocket 配合设计

| 项 | 内容 |
|----|------|
| **文档编号** | SPEC-DEV-BUS-001 |
| **状态** | 待审阅 |
| **日期** | 2026-07-31 |
| **范围** | 执法仪 App（`android-app`）配合后端「交互全走 WebSocket」 |
| **读者** | 设备端开发 / 后端联调 / 测试 |
| **相关规格** | [公司任务房媒体端](2026-07-31-device-task-room-design.md)、[ADR-0005](../../决策记录/0005-company-task-room.md)、[ADR-0002](../../决策记录/0002-trtc-for-command-calls.md) |
| **领域词** | [`CONTEXT.md`](../../../CONTEXT.md)（扫码绑定、解绑、公司任务房、画面监看、指挥连线、AI 助手预热保活） |
| **非范围** | 后端多实例连接路由实现细节、Web 控制台 WS、chat/vision 迁入 Bus、上传改 WS、未占用常连运维通道 |

---

## 1. 目的与决策摘要

### 1.1 目的

在后端将**业务交互**收敛为 WebSocket 的前提下，规定执法仪如何建连、鉴权、收发统一信封、与现有任务房/TRTC 状态机及 AI Realtime 协同，并在过渡期用 HTTP poll 同构兜底，避免串房、双执行与生命周期混乱。

### 1.2 已确认决策

| 编号 | 决策 |
|------|------|
| D1 | **通道范围 A**：交互（请求/响应 + 下行推送）走业务 WebSocket；TRTC 仍承载音视频；大文件上传仍 HTTP；AI Realtime **独立** WebSocket |
| D2 | **生命周期 C**：扫码绑定成功后同时拉起业务 Bus 与 AI Realtime；解绑/占用失效一并拆除；指挥连线打断 AI 时 **Bus 保持** |
| D3 | **兜底 B**：过渡期保留 HTTP poll；与 Bus（及可选 MQTT）对同一逻辑指令使用同一 `delivery_id` 去重 |
| D4 | **架构**：`DeviceBusClient` + `DeviceBusRouter` 统一信封；媒体状态机不改，仍由任务房规格约束 |

### 1.3 设计原则

1. **Bus 只换管道，不改媒体语义**：`task_room_join` / `leave` 与 `IDLE/HOLDING/WATCHING/IN_CALL` 以任务房规格为准。  
2. **AI 与业务隔离**：禁止在 Bus 上传输 PCM/JPEG；禁止把任务房信令塞进 Realtime 连接。  
3. **占用驱动连接**：未占用不建 Bus；在线可见性不等于未占用可进房。  
4. **通道无关执行**：设备只认 Router + `delivery_id`，不认「来自 WS / poll / MQTT」。  
5. **SecretKey 永不落端**：UserSig 仅出现在下行 payload 中，用法同任务房规格。

---

## 2. 系统上下文

```text
扫码绑定成功
    │
    ├─ DeviceBusClient  ──WS──►  Backend 业务交互
    │         ▲                    │
    │         │ 同构信封            │ 定点推送
    │         │                    ▼
    │    HTTP poll 兜底      task_room_* / ack / …
    │
    └─ RealtimeVoiceClient ──WS──► Backend ──► 百炼 Realtime
              （独立；指挥可打断）

媒体：CommandCall* ──TRTC──► 腾讯云房间（room-task-*）
上传：ApiClient ──HTTPS──► /v1/… multipart 等
```

**当前 AI 现状（保留）**

| 能力 | 协议 | 路径 |
|------|------|------|
| PTT 全双工 Realtime | WebSocket | `/v1/realtime/voice` |
| 文本对话 / 识图 | HTTP | `POST /v1/chat`、`POST /v1/vision` |

本期不把 chat/vision 并入 Bus。

---

## 3. 通道分工

| 通道 | 用途 | 备注 |
|------|------|------|
| **业务 Bus WS**（新） | 任务房 join/leave、ack、状态、原 MQTT 类交互 | 仅 JSON 文本帧 |
| **AI Realtime WS**（现有） | PTT PCM / JPEG、会话事件 | 独立连接与协议 |
| **HTTP** | 大文件上传；**过渡期** poll 同构兜底；chat/vision | poll 产出与 Bus 同构信封 |
| **TRTC** | 监看/连线音视频 | 后端只签凭证 |
| **MQTT**（迁移可选） | 过渡期可双投 | 载荷须规范成同一信封后进 Router；稳定后删除 |

---

## 4. 连接生命周期与鉴权

### 4.1 生命周期

```text
扫码绑定成功
  ├─ 拉起 DeviceBusClient（业务 WS）
  └─ 拉起 RealtimeVoiceClient（AI 预热）

画面监看（WATCHING）
  └─ Bus 保持；AI 不打断

指挥来电 / IN_CALL
  └─ 打断并断开 AI；Bus 保持（继续收 leave/换房）

指挥挂断且仍占用
  └─ 再次预热 AI；Bus 已连则复用，不无故重连风暴

解绑 / 占用失效 / 关机等效解绑
  ├─ Bus 断开
  ├─ AI 断开
  └─ 媒体 leave + 退 TRTC（任务房规格）
```

### 4.2 鉴权

- Bus 连接使用 `Authorization: Bearer <session_token>`（与 Realtime 一致；实现受限时允许 query 兜底，但优先 Header）。  
- 占用失效（HTTP 401 或约定 WebSocket close code）：清空本机占用展示缓存，拆除 Bus + AI，引导重新扫码；若在房则执行 leave。  
- 未占用：**不建立** Bus。

### 4.3 重连

- Bus：指数退避（建议对齐现 MQTT：首档约 5s，封顶约 60s）。  
- 重连成功后可发送可选 `hello` / `sync`；**仍运行 HTTP poll** 补单。  
- AI：沿用现有静默重连策略；与 Bus **互不拖垮**（一侧失败不主动拆另一侧，解绑除外）。

---

## 5. 消息信封与路由

### 5.1 统一信封

Bus WS 与 poll（及迁移期 MQTT）使用同构 JSON：

```json
{
  "v": 1,
  "type": "task_room_join",
  "request_id": null,
  "delivery_id": "必填-去重键",
  "ts": 1730000000000,
  "payload": {}
}
```

| 字段 | 规则 |
|------|------|
| `v` | 协议版本；设备不识别则忽略并打日志 |
| `type` | 路由键（如 `task_room_join` / `task_room_leave` / `ack`） |
| `request_id` | 设备发起的 RPC 对齐；纯下行推送可空 |
| `delivery_id` | **全局去重键**（后端生成）；同一逻辑指令在所有通道必须相同 |
| `ts` | 毫秒时间戳；辅助观测，不去重依赖 |
| `payload` | 业务体；任务房字段与任务房规格一致 |

任务房：`type` = `task_room_join` | `task_room_leave`；`payload` 语义不变，仅外挂信封。

### 5.2 DeviceBusRouter

```text
Bus WS onMessage ──┐
                   ├─► DeviceBusRouter ──► SessionManager / CommandCall*
HTTP poll 项 ──────┘         │
MQTT（迁移期）─────┘         ├─ delivery_id 已见 → 丢弃
                             └─ 未见 → 分发 + 记入去重窗
```

- 去重窗：进程内 LRU/环形缓冲；建议最近 **N=200** 或 **TTL≈10min**（实现期可调，须可测）。  
- `MqttTopicRouter`：迁移期将 MQTT 载荷规范为同一信封再进入 Router；稳定后删除 MQTT 入口。

### 5.3 上行

| type | 何时 | 要点 |
|------|------|------|
| `ack` | join 处理成功后（建议 SDK enter 成功或进入目标模式后） | 关联原指令 `delivery_id`；优先 Bus；失败可降级 HTTP ack；失败只日志不回滚进房 |
| `hello` / `sync` | Bus 重连成功（可选） | 后端可补推未 ack 指令或当前应在房快照；无则依赖 poll |
| 其它 RPC | 逐步迁移的交互 | 带 `request_id`；后端回同 id 的 result/error |

大文件与 chat/vision：**本期仍 HTTP**。

### 5.4 与 AI 的边界

- Bus **仅 JSON 文本帧**。  
- `RealtimeVoiceClient` / `RealtimeVoiceProtocol` 不经 Router。  
- 指挥打断只作用于 AI，不关闭 Bus。

---

## 6. 与任务房 / TRTC 衔接

### 6.1 映射

| Bus / poll `type` | 设备行为 |
|-------------------|----------|
| `task_room_join` | 进/换房；按 `push_video` 进入 `HOLDING` / `WATCHING`；成功后 ack |
| `task_room_leave` | 幂等退房 → `IDLE` |
| 兼容 `watch_*` / `call_*` | 迁移期可走旧 Controller 路径；新联调以 `task_room_*` 为准 |

媒体不变式（MUST）继承任务房规格：一机一房；换 `room_id` 先完整 leave 再 enter；同房重复 join 幂等并可更新 `push_video`。

### 6.2 事件矩阵

| 事件 | Bus | AI Realtime | TRTC |
|------|-----|-------------|------|
| 绑定成功 | 连接 | 预热 | 未进房则为 IDLE |
| join 监看 | 保持 | 不打断 | 共摄推流（若 `push_video`） |
| 升指挥 / IN_CALL | **保持** | **打断并断开** | 同房；灯效/PTT 按任务房规格 |
| 指挥结束且仍占用 | 保持 | 再预热 | 按 leave 或降级信令 |
| 解绑 | 断开 | 断开 | leave + 退房 |

### 6.3 断线策略

- Bus 短暂断开：**不**自动退 TRTC（避免抖动误踢）；poll 继续执行 leave/换房 join。  
- Bus 重连：可选 sync + 持续 poll。  
- 解绑/占用失效：即使 Bus 已断，本地必须完成 AI、TRTC、共摄 teardown。

### 6.4 设备明确不做

- 不因 Bus 断线自行建房或猜测 `room_id`  
- 不在 Bus 上拉取/渲染其它设备画面或传输音视频  
- 不在 Bus 与 Realtime 之间混帧  
- 不维护公司组织树或任务房目录（组织在云）

---

## 7. 模块落点

| 模块 | 代码锚点（预期） | 职责 |
|------|------------------|------|
| 业务 WS 客户端 | `DeviceBusClient`（新） | 连接、鉴权、重连、收发文本帧 |
| 统一路由 | `DeviceBusRouter`（新，或由 `MqttTopicRouter` 演进） | 解信封、去重、分发 |
| 会话编排 | `SessionManager` | 绑定/解绑时启停 Bus 与 AI；串行消费信令 |
| 信令解析 | `CommandCallSignalParser` | payload → 凭证 + kind + `push_video` |
| 房间控制 | `CommandCallController` 等 | 媒体状态机；**不感知传输通道** |
| AI | `RealtimeVoiceClient` | 独立 WS；受指挥门闩 |
| Poll | 既有 HTTP poll | 产出同构信封，进入 Router |

---

## 8. 失败模式

| 场景 | 期望行为 |
|------|----------|
| Bus 连不上 / 频繁断 | 退避重连；poll 继续；已在房不因断线自动退房 |
| poll 也失败 | 保持当前媒体态；恢复后补单；解绑仍本地强制 leave |
| 同 `delivery_id` 多通道到达 | Router 只执行一次 |
| ack 失败 | 只日志；不回滚已进房 |
| session_token 失效 | 拆 Bus + AI；清占用缓存；引导重扫码；在房则 leave |
| join 凭证/进房失败 | 回到可解释态（IDLE 或原房）；可上报错误（若后端提供 type） |
| 指挥中 Bus 断 | AI 本已断；媒体尽量保持；poll/`leave` 收口 |
| 解绑时 Bus 已死 | 本地 teardown 完整（AI、TRTC、共摄；去重窗可清空） |

---

## 9. 迁移与文档

### 9.1 双投窗口

1. 后端对同一逻辑指令：Bus 推送 +（可选）MQTT + poll 队列，**同一 `delivery_id`**。  
2. 设备只执行 Router 分发结果。  
3. 退出标准：仅 WS、仅 poll、WS+poll 联调通过且无双进房后，删除 MQTT；再评估删除 poll。

### 9.2 关联文档修订（实现前）

| 文档 | 动作 |
|------|------|
| [任务房媒体端规格](2026-07-31-device-task-room-design.md) | 信令主路径改为 Bus WS；MQTT 标为迁移可选；poll 改为同构信封；去重键对齐 `delivery_id` |
| [`通信协议规范.md`](../../架构与数据/协议/通信协议规范.md) / [`四端连接总览.md`](../../开发指南/四端连接总览.md) | 通道表：MQTT 主路径 → Bus WS；保留 poll 过渡说明 |

---

## 10. 验收清单

1. 绑定 → Bus 与 AI 均连接；解绑 → 均断开且 TRTC 清空。  
2. 仅 Bus：`task_room_join` / `leave` 进退房正确。  
3. 仅 poll：同构信封仍能完成进退房。  
4. Bus+poll（及迁移期 MQTT）双投：不双进房、ack 无有害副作用。  
5. 监看不打断 AI；升指挥打断 AI，Bus 仍连接。  
6. 指挥挂断后 AI 再预热；Bus 无重连风暴。  
7. 更换 `room_id`：先 leave 再 enter。  
8. token 失效：回到未占用体验，无僵尸推流。

---

## 11. 非目标（本期）

- 将 `/v1/chat`、`/v1/vision` 迁入 Bus  
- 照片/录像上传改走 WebSocket  
- 未占用设备常连运维/配置推送  
- 强制删除 poll（仅定义退出条件）  
- 合并 AI Realtime 与业务 Bus 为同一条连接  

---

## 12. 风险与已知弊端

1. 占用期间双 WebSocket（Bus + AI），弱网与耗电高于单 MQTT。  
2. 过渡期 WS + poll + 可选 MQTT 三通道并存，去重与联调成本高。  
3. 未占用不可见、无运维推送（刻意取舍）。  
4. Bus 不具备 MQTT QoS1 持久语义；依赖重连、poll 与 `delivery_id`。  
5. 后端需维护设备连接表与定点推送（多实例时另需路由/pubsub）。

缓解：优先落实双投窗口与统一去重键；媒体状态机与通道解耦，避免在 Bus 层复制进房逻辑。
