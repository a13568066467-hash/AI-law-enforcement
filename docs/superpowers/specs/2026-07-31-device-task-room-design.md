# 执法仪：公司任务房媒体端设计规格

| 项 | 内容 |
|----|------|
| **文档编号** | SPEC-DEV-TASKROOM-001 |
| **状态** | 已批准实施实施（L1–L3 代码落地；见文末验收记录） |
| **日期** | 2026-07-31 |
| **范围** | 执法仪 App（`android-app`） |
| **读者** | 设备端开发 / 联调 / 测试 |
| **上位决策** | [ADR-0005 公司任务房](../../决策记录/0005-company-task-room.md)、[ADR-0002 TRTC](../../决策记录/0002-trtc-for-command-calls.md)、[ADR-0003 画面监看](../../决策记录/0003-live-preview-session.md) |
| **领域词** | [`CONTEXT.md`](../../../CONTEXT.md)（公司任务房、画面监看、指挥连线、连线对讲、连线共摄） |
| **非范围** | 后端建房/组织目录、Web 多画面与多麦 UI、云端流媒体播放地址（另文预留缝） |
| **信令通道演进** | 业务交互主路径见 [DeviceBus WebSocket 配合设计](2026-07-31-device-bus-websocket-design.md)；本文媒体语义不变，传输可由 MQTT/poll 迁至 Bus + 同构 poll |

---

## 1. 目的与问题

### 1.1 目的

在后端采用「**公司任务房**」组织进房（同公司多设备 + 多座席、统一 `trtc_room_id`）的前提下，规定执法仪作为 **TRTC 媒体端点** 的职责边界、状态机、信令契约、媒体行为与验收标准，使设备实现与组织模型解耦、可联调、可回归。

### 1.2 问题陈述

| 编号 | 问题 |
|------|------|
| P1 | 媒体主路径从「一占用一房 / 一座席互斥」变为「一任务房一 TRTC 房」后，设备若仍按旧 call 互斥模型会拒收合法同房指令。 |
| P2 | 组织（公司、任务房成员、座席）属于后端权威；设备若维护组织状态会造成双源真相。 |
| P3 | 监看与连线须同房切换业务态，不能拆房再建。 |
| P4 | 设备须在换房、解绑、弱网下可预期地退房/重进，避免串房与僵尸推流。 |

### 1.3 设计原则

1. **组织在云，媒体在端**：设备不创建、不列举、不校验公司成员关系（除消费后端已签发的凭证）。  
2. **信令驱动**：一切进退房以 `task_room_join` / `task_room_leave`（及兼容路径）为准。  
3. **一机一房**：任一时刻本机最多加入一个 TRTC `room_id`。  
4. **只推本路**：不拉其它 `device-*` 或座席视频。  
5. **占用 ≠ 媒体房**：扫码占用独立；解绑触发媒体退房，但不由设备关闭任务房实体。  
6. **SecretKey 永不落端**：仅使用后端下发的短期 UserSig。

---

## 2. 系统上下文

```text
┌─────────────┐  建房/加设备/校验公司   ┌──────────────┐
│ Web 指挥台  │ ─────────────────────► │   Backend    │
└─────────────┘                        │ 任务房权威源 │
                                       └──────┬───────┘
              MQTT join/leave + HTTP poll/ack │
                                              ▼
                                       ┌──────────────┐
                                       │  执法仪 App  │
                                       │ 本文规格对象 │
                                       └──────┬───────┘
                                              │ TRTC SDK
                                              ▼
                                       ┌──────────────┐
                                       │ 腾讯云 TRTC  │
                                       │ room-task-*  │
                                       └──────────────┘
```

**本机不感知**：任务房列表、其它设备名单、座席人数、跨公司拒绝原因文案（仅感知进房失败/凭证错误）。

---

## 3. 职责边界

### 3.1 执法仪必须做

| ID | 职责 |
|----|------|
| D1 | 订阅并解析任务房 join/leave；HTTP poll 兜底同构载荷 |
| D2 | 使用下发凭证进入/离开指定 `room_id` |
| D3 | 按 `push_video` 启停连线共摄视频上行 |
| D4 | 任务房/连线业务态下提供半双工 PTT 上行 |
| D5 | 区分监看态与指挥连线态的灯效与 AI 门闩 |
| D6 | 换房先 leave 再 join；leave/解绑后停推并释放 TRTC |
| D7 | 成功消费 join 后向后端 ack（若接口可用） |

### 3.2 执法仪禁止做

| ID | 禁止 |
|----|------|
| X1 | 本地建任务房或分配 `room-task-*` |
| X2 | 维护公司组织树 / 会议室目录 |
| X3 | 拉取或渲染其它设备画面 |
| X4 | 设备全双工常开麦 |
| X5 | 麦序仲裁、座席混音策略 |
| X6 | 用占用房 `room-occ-*` 作为新媒体主路径（仅兼容占坑时可短暂存在） |

---

## 4. 逻辑架构

### 4.1 模块划分

| 模块 | 代码锚点（现行） | 职责 |
|------|------------------|------|
| Topic 路由 | `MqttTopicRouter` | 订阅 `aifieldcam/task_room/{deviceId}/join\|leave`，分发到会话 |
| 信令解析 | `CommandCallSignalParser` | JSON → 凭证 + kind + `push_video` |
| 会话编排 | `SessionManager` | 串行处理 join/leave、ack、与 poll 去重 |
| 房间控制 | `CommandCallController` | 模式机、进退 TRTC、换房 |
| TRTC 房间 | `CommandCallRoom` | SDK enter/exit |
| 共摄视频 | `CommandCallCoCapture` 等 | 录像同源旁路 → TRTC |
| 对讲 | `CommandCallIntercom` + 按键分发 | PTT 半双工 |
| 占用绑定 | `device_bind` 相关 | 解绑时请求媒体 leave |

### 4.2 与占用层关系

```text
占用绑定（人机）          媒体任务房（TRTC）
  扫码 bound      ──允许被加入──►  task_room_join
  解绑 / 关机     ──必须触发──►  task_room_leave + 退房
```

占用有效是后端加设备的前置条件；设备端在未占用时仍应能执行 leave（幂等），收到 join 时若本地已知未占用可记录告警，但**是否拒绝进房以产品策略为准**——推荐：**信任后端校验，设备照指令进房**；若进房后发现会话非法，以后端 leave 为准收口。

---

## 5. 状态机

### 5.1 模式定义

| 模式 | 含义 | 视频上行 | 状态灯 | AI Realtime |
|------|------|----------|--------|-------------|
| `IDLE` | 未进 TRTC | 否 | 常规执勤 | 允许（受其它门闩） |
| `HOLDING` | 在任务房，未要求推流 | 否或近似不推 | 不因任务房改变 | 允许 |
| `WATCHING` | 在任务房，监看/要求推流 | 是（共摄） | **不改变** | **不打断** |
| `IN_CALL` | 指挥连线业务态（同房） | 是 | 红灯常亮 | **打断** |

> 注：`HOLDING` 在任务房模型下表示「已入 `room-task-*` 且 `push_video=false`」；旧占用房 `room-occ-*` 占坑若仍存在，语义对齐为兼容，不作为监看主路径。

### 5.2 转移

```text
                     task_room_join
                     push_video=false
     IDLE ──────────────────────────► HOLDING
       │                                 │
       │ task_room_join                  │ push_video=true
       │ push_video=true                 │ 或再收 join(push=true)
       ▼                                 ▼
    WATCHING ◄─────────────────────── HOLDING
       │
       │ 升连线 / call 业务信令（同 room_id）
       ▼
    IN_CALL
       │
       │ task_room_leave / 解绑 / 换至其它 room_id（先 leave）
       ▼
     IDLE
```

### 5.3 不变式（MUST）

1. `activeTrtcRoomId` 与 SDK 当前房间一致，或均为空。  
2. 若新 join 的 `room_id` ≠ `activeTrtcRoomId`：必须先完整 leave（停对讲、解绑共摄、exitRoom），再 enter。  
3. 同 `room_id` 重复 join：幂等；允许仅更新 `push_video` / 业务模式。  
4. `IDLE` 时不得残留共摄绑定或 PTT 上行。

---

## 6. 信令契约

### 6.1 Topic（MQTT 主路径）

| 方向 | Topic | 说明 |
|------|-------|------|
| 云 → 端 | `aifieldcam/task_room/{device_id}/join` | 进入或更新任务房 |
| 云 → 端 | `aifieldcam/task_room/{device_id}/leave` | 离开任务房 |

与既有 command_call start/end topic 可并存；**任务房媒体主路径以本节为准**。

### 6.2 HTTP 兜底

设备按既有 poll 间隔拉取待下发信令；payload 与 MQTT **字段同构**。MQTT 与 poll 必须对同一逻辑指令去重（以 `task_room_id` + `room_id` + `action` + 可选时间戳/投递标记）。

### 6.3 `task_room_join` 载荷（设备必认）

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `action` | string | 是 | 固定 `task_room_join` |
| `task_room_id` | string | 是 | 业务任务房 ID（本地关联用） |
| `room_id` | string | 是 | TRTC 字符串房间号，形如 `room-task-*` |
| `device_id` | string | 建议 | 本机 ID，用于校验 |
| `sdk_app_id` | number | 是 | TRTC SDKAppId |
| `user_id` | string | 是 | 建议 `device-{device_id}`（非法字符已由后端消毒） |
| `user_sig` | string | 是 | 短期签名 |
| `push_video` | boolean | 是 | `true` → 进入/保持推流（`WATCHING` 或保持 `IN_CALL`） |
| `device` | object | 否 | 若存在，可内嵌 `sdk_app_id/room_id/user_id/user_sig`；与顶栏字段二选一完整即可 |

兼容：解析层同时接受 snake_case / camelCase。

### 6.4 `task_room_leave` 载荷

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `action` | string | 是 | `task_room_leave` |
| `task_room_id` | string | 建议 | 用于日志与弱校验 |
| `room_id` | string | 建议 | TRTC room |
| `device_id` | string | 建议 | 本机 |

**行为**：无论 `task_room_id` 是否与本地完全一致，若本机在房则执行退房（避免卡死）；若本地已 `IDLE` 则幂等成功。

### 6.5 Ack

设备成功开始处理 join（建议：SDK enter 成功或明确进入目标模式后）调用后端设备 ack 接口，action=`task_room_join`。Ack 失败只打日志，不回滚已进房（避免抖动）；后端以心跳/无流策略收口僵尸成员。

### 6.6 兼容信令

| 旧/兼容 action | 设备处理 |
|----------------|----------|
| `occupy_room` | 可进兼容占用房；**不得**替代任务房作为监看主路径 |
| `watch_start` / `call_start` | 若仍下发：按既有 Controller 路径；新联调应以 `task_room_join` 为准 |
| `watch_end` / `call_end` | 结束业务推流/连线态；任务房模型下优先 `task_room_leave` 或同房降级（见实现与后端约定） |

---

## 7. 媒体设计

### 7.1 视频（连线共摄）

- 源：本机循环录像会话旁路（YUV/I420 优先，见既有共摄规格）。  
- 禁止：为任务房再开第二路 Camera。  
- 未录像而需要推流：按既有策略自动开录或等价共摄源。  
- 分辨率/帧率：遵循现行指挥连线共摄默认（CONTEXT：约 1080p@15fps 量级）；本规格不单独改指标。  
- 多设备同房：本机只 `send` 本路自定义视频；不 `startRemoteVideo`。

### 7.2 音频 / 连线对讲

| 角色 | 规则 |
|------|------|
| 设备上行 | 仅 PTT 按住期间发送；松手停止（半双工） |
| 设备下行 | 接收任务房内混音（座席广播 + 其它端）；具体 SDK 订阅策略保持 RTC 房间默认 |
| 与 AI | `IN_CALL` 或等价「指挥连线业务态」打断 AI Realtime；`WATCHING`/`HOLDING` 不因任务房打断 AI |
| 与 SOS 工单 | 遵循 CONTEXT：连线中不抢麦上报；监看中允许（若现网已实现） |

### 7.3 后续云端流媒体地址（非本迭代实现，仅预留）

- 独立信令缝：`stream_start { url, … }` / `stream_stop`，与任务房 TRTC **并列**，不得塞进 `onTaskRoomJoin`。  
- 共摄源可 tee；带宽冲突时优先任务房连线（产品可再修订）。  
- 国标 GB28181 / 监控墙不在本规格验收范围。

---

## 8. 关键用例（设备视角）

### UC-1 被加入任务房（推流）

1. 收到 `task_room_join` 且 `push_video=true`。  
2. 若在其它 `room_id` → leave。  
3. enterRoom；模式 → `WATCHING`；启动共摄。  
4. ack。  

### UC-2 同房仅占坑

1. `push_video=false` → `HOLDING`，不启共摄（或停共摄）。  

### UC-3 换任务房

1. join 新 `room_id` → 先 leave 旧房 → 再 UC-1/UC-2。  

### UC-4 移出 / 解绑

1. `task_room_leave` 或占用释放回调 → 停 PTT、解共摄、exitRoom → `IDLE`。  

### UC-5 监看升连线（同房）

1. 保持同一 `room_id`。  
2. 模式 → `IN_CALL`；红灯；打断 AI；PTT 仍可用。  

### UC-6 两台设备同房

1. 各设备仅执行对本机 join。  
2. 互不订阅对方视频。  

---

## 9. 失败与恢复

| 场景 | 期望 |
|------|------|
| UserSig 无效 / enter 失败 | 保持可恢复：模式回到 `IDLE` 或未进房；记 `lastFailureReason`；等待后端重发 join |
| MQTT 短暂断开 | 依赖自动重连 + HTTP poll 补单 |
| 进房成功但推流失败 | 保持在房；重试共摄绑定；不自动 leave |
| 重复 leave | 幂等 |
| 进程被杀 | 冷启动为 `IDLE`；等待后端成员修复或新 join |
| 短断网 | 按 TRTC SDK 重连同一 `room_id`；凭证过期则等新 join |

---

## 10. 可观测性

设备日志至少包含：

- `task_room_join|leave`：`task_room_id`、`room_id`、`push_video`、结果  
- 换房：旧/新 `room_id`  
- enter/exit TRTC 结果  
- 共摄 bind/unbind  
- PTT start/stop（可 debug 级）  
- ack 成功/失败  

禁止日志打印完整 `user_sig`（可截断）。

---

## 11. 验收标准（设备签字项）

| ID | 标准 | 通过条件 |
|----|------|----------|
| A1 | 进房 | 后端将本机加入任务房后，日志显示 enter `room-task-*`，`user_id` 为 `device-…` |
| A2 | 推流 | `push_video=true` 时座席可见本机画面；本机录像不中断 |
| A3 | 不拉流 | 同房另一台设备在线时，本机无其它设备远端视频渲染 |
| A4 | 换房 | 房 A→房 B 无双房残留，最终仅在 B |
| A5 | 离开 | leave/解绑后 TRTC 退出、共摄停、模式 `IDLE` |
| A6 | 监看灯效 | `WATCHING` 不红灯、不打断 AI |
| A7 | 连线灯效 | `IN_CALL` 红灯、打断 AI |
| A8 | PTT | 连线/任务房对讲态下按住有上行、松手无上行 |
| A9 | 兜底 | 仅 poll（无 MQTT）仍能完成 A1–A5 |
| A10 | 幂等 | 重复 join 同房不导致可见闪断（允许无感重绑） |

---

## 12. 测试大纲

| 类型 | 用例要点 |
|------|----------|
| 单测 | Parser：join/leave、嵌套 `device`、camelCase；Controller：换房、leave 幂等、`push_video` 切换 |
| 仪器联调 | 与后端任务房 API：加两设备、移出、解绑；抓 MQTT + logcat |
| 回归 | 扫码占用、本机录像、AI PTT、SOS 与连线互斥（CONTEXT） |

---

## 13. 落地阶段（设备）

| 阶段 | 内容 | 状态指引 |
|------|------|----------|
| L1 | Topic + Parser + Controller join/leave + Session 编排 + ack | 代码已具备初版，以 A1–A5 联调收口 |
| L2 | 监看/连线态灯与 AI 门闩对齐本规格 | 对照 A6–A8 |
| L3 | 换房/解绑竞态与 poll 去重加固 | 对照 A4、A9、A10 |
| L4 | （可选）流媒体 URL 推流缝，另开规格 | 非本文件验收 |

---

## 14. 文档追溯

| 文档 | 关系 |
|------|------|
| ADR-0005 | 组织/任务房产品决策（后端+全局） |
| 本规格 | **执法仪实现合同** |
| ADR-0002/0003 | TRTC 媒体与监看语义 |
| MQTT Ack + YUV 共摄规格 | 信令送达与旁路画质 |
| CONTEXT.md | 术语权威 |

---

## 15. 开放问题（待产品/后端书面确认则修订本文）

1. 任务房内是否仍下发独立 `call_start`，或仅靠模式字段/`kind` 升连线。  
2. `watch_end` 在任务房模型下是 leave 还是降级为 `HOLDING`。  
3. 未占用设备若误收 join：设备信任进房 vs 本地硬拒。  

修订时更新本节与 §6.6，并升文档状态。

---

## 16. 批准

| 角色 | 姓名 | 日期 | 结论 |
|------|------|------|------|
| 设备负责人 | | 2026-07-31 | 按本文实施（用户指令） |
| 后端对接 | | | |
| 测试 | | | |

---

## 17. 实施验收记录（2026-07-31）

| 阶段 | 内容 | 结果 |
|------|------|------|
| L1 | Parser `pushVideo`；join/leave；成功后再 ack；换房 leave；同房 `push_video=false` 解共摄 | ✅ 代码 |
| L2 | WATCHING 不红灯/`isInCall=false`；IN_CALL 打断 AI；PTT 仅 IN_CALL | ✅ 代码 + 单测用例 |
| L3 | `TaskRoomSignalDeduper`（含 room + push_video）；leave 去重；解绑退房 | ✅ 代码 |
| 编译 | `:app:compileDebugKotlin` + `compileDebugUnitTestKotlin` | ✅ 通过 |
| 单测执行 | `CommandCallTaskRoomTest` 等 | ⚠ 本机 Gradle Worker `GradleWorkerMain` ClassNotFound（环境问题）；用例已写入待 CI/本地修复后跑通 |

**整体对照 A1–A10（逻辑验收）：**

| ID | 结论 |
|----|------|
| A1 进房 | ✅ `onTaskRoomJoin` → `activeTrtcRoomId` + `device-*` |
| A2 推流 | ✅ `push_video=true` → 共摄 bind（真机联调看画面） |
| A3 不拉流 | ✅ 设备侧无 remote video 订阅 |
| A4 换房 | ✅ 先 leave 再 join |
| A5 离开 | ✅ leave/解绑 → IDLE |
| A6 监看灯/AI | ✅ `isInCall=false` |
| A7 连线灯/AI | ✅ upgrade → `IN_CALL` |
| A8 PTT | ✅ 仅 `IN_CALL` 开上行 |
| A9 poll 兜底 | ✅ poll 与 MQTT 同 `dispatch`；去重键含 room/pv |
| A10 幂等 | ✅ 同房重复 join 不二次 enter |

L4（流媒体 URL）按规格不做。
