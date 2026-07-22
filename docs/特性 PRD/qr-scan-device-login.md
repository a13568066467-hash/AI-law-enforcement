# PRD: 扫码绑定登录重构

> 状态: Shipped | 日期: 2026-07-14 · 手机端落地 2026-07-15  
> 领域术语: 根目录 `CONTEXT.md`（本 PRD 落地时同步更新）

---

## Problem Statement

当前执法仪采用「一人一台专属绑定 + 执法仪端 8 步注册 + 人脸登录」模式，与现场执勤方式不符：同一公司有多台设备、多名在岗人员，应能随意拿取未占用设备执勤，而非每人固定一台。设备关机后不应保留上一任执勤员的登录态；人员档案应由管理后台与手机 App 维护，执法仪只负责展示二维码、接收绑定结果与只读资料。设置菜单中亦有过时入口（注册、注销、基础配置、息屏侧键、录像、退出登录、隐私政策），需要精简并统一视觉风格。相册 Tab 目前仅展示拍照，未纳入循环录像，执勤员无法在同一处查阅本机影像资料。

## Solution

将执法仪登录重构为 **公司设备池 + 临时扫码占用**：

1. 设备入库时由管理后台绑定 `company`；执勤员用本仓库 **`mobile-app`**（`com.aifieldcam.mobile`）扫描执法仪「我的」页短期二维码完成绑定。
2. 云端校验：人员在岗、人员与设备同公司、一人一机/一机一人、目标设备未被他人占用。
3. 执法仪轮询绑定状态，成功后拉取人员摘要；「人员信息」页展示只读资料；未绑定时仅提示并引导回「我的」扫码。
4. 关机或设置内「解绑」结束本机缓存与云端当前占用，保留使用历史；息屏/后台不清。
5. 执法仪完全移除人脸注册/登录与 8 步注册向导；设置保留人员信息、解绑、关于我们；相册统一展示照片与录像。

## User Stories

1. As a 在岗巡查员，I want to 用手机 App 扫描执法仪二维码完成登录，so that 我可以快速在任意公司设备上开始执勤而无需在执法仪上注册。
2. As a 在岗巡查员，I want to 扫码时若该设备已被他人占用则收到明确拒绝，so that 我不会误踢正在执勤的同事。
3. As a 在岗巡查员，I want to 若我已在另一台设备占用中则扫码失败并被告知，so that 我必须先结束旧机占用才能换机。
4. As a 在岗巡查员，I want to 在「我的」主页看到大号二维码与扫码说明（未绑定时），so that 我知道如何完成绑定。
5. As a 在岗巡查员，I want to 绑定成功后「我的」主页展示人员摘要，so that 我确认当前执勤身份。
6. As a 在岗巡查员，I want to 在「人员信息」查看完整只读资料（已绑定时），so that 我核对工号、组织等信息。
7. As a 未绑定设备的执勤员，I want to 打开「人员信息」时看到「需先绑定」提示与返回扫码引导，so that 我不会在空白页困惑。
8. As a 在岗巡查员，I want to 在设置中手动解绑，so that 我不关机也能把设备交给同事扫码。
9. As a 在岗巡查员，I want to 设备关机重启后本机无绑定且须重新扫码，so that 下一班不会沿用上一任身份。
10. As a 系统，I want to 整机断电/重启时结束该设备云端当前占用并写入使用历史，so that 设备回到可扫状态且可追溯。
11. As a 系统，I want to 息屏与 App 后台时保持占用不变，so that 执勤中不会误掉线。
12. As a 管理后台操作员，I want to 设备入库时绑定所属公司，so that 只有本公司人员在岗者能扫该设备。
13. As a 手机 App 用户，I want to 扫码后由后端确认绑定，so that 身份校验在云端统一完成。
14. As a 手机 App 用户，I want to 通过注册 API 自助建档（含人脸等），so that 不必依赖执法仪注册。
15. As a 管理后台操作员，I want to 预录入人员在岗档案，so that 人员可不经过手机自助注册即可被扫码绑定。
16. As a 执法仪 App，I want to 轮询短期 bind token 的状态直至 bound，so that 无需 WebSocket 即可感知扫码成功。
17. As a 执法仪 App，I want to 二维码 token 2–5 分钟过期并自动刷新，so that 扫码链接不可长期复用。
18. As a 执勤员，I want to 在相册 Tab 同时查看本机拍摄照片与循环录像 MP4，so that 现场影像在一处可查阅。
19. As a 执勤员，I want to 设置菜单仅保留人员信息、解绑、关于我们，so that 专机界面简洁。
20. As a 执勤员，I want to 设置子页视觉与「我的」深色工地风一致，so that 界面风格统一。
21. As a 系统，I want to 拒绝非本公司在岗人员扫码绑定，so that 组织隔离得到 enforce。
22. As a 系统，I want to 绑定成功后向执法仪下发会话 token 与人员摘要，so that 后续 AI/录像等能力可识别当前执勤员。
23. As a 执勤员，I want to 不再看到人脸登录、注册向导、退出登录、基础配置、息屏侧键、录像菜单、隐私政策，so that 过时能力不干扰执勤。
24. As a 运维人员，I want to 云端保留设备使用历史（谁、哪台、起止时间），so that 可对账与接入大屏统计。
25. As a 故障流程中的待工人员，I want to 在新执法仪上通过扫码绑定恢复执勤，so that 不必再走执法仪 8 步注册。

## Implementation Decisions

### 接缝（Seams）

本特性优先复用 **单一后端绑定会话接缝** 作为权威源，Android 与手机 App 均经此接缝读写，避免多套登录路径并存：

| 接缝 | 职责 | 说明 |
|------|------|------|
| **Device bind session（后端）** | 签发短期 token、确认绑定、查询状态、解绑/关机结束占用、写入使用历史 | **最高接缝**；替代执法仪端 patrol 人脸登录路径 |
| **Device session facade（执法仪 SessionManager）** | 本机 bound/unbound 状态、轮询、profile 缓存、关机清本地 | 执法仪侧唯一会话入口 |
| **Me / Settings UI** | 二维码展示、菜单精简、人员信息空态 | 纯展示，不写云端档案 |
| **Album media index** | 聚合拍照 JPEG 与循环录像 MP4 列表 | 只读本地媒体索引 |

### 后端

- **`recorders` 表**：增加 `company`（或与 `officers.company` 对齐的字段）；入库 API 由管理后台写入（本 PRD 定义契约，管理后台 UI 可后续实现）。
- **新表 `device_bind_tokens`**（或等价）：`device_id`、`token`、`expires_at`、`status`（pending/consumed/expired）。
- **新表 `device_occupancy`**（当前占用）：`device_id` → `employee_id`、`started_at`；唯一约束保证一机一人。
- **新表 `device_usage_history`**（或 occupancy 软删除留痕）：`device_id`、`employee_id`、`started_at`、`ended_at`、`end_reason`（shutdown/unbind/admin）。
- **人员占用约束**：`employee_id` 在 `device_occupancy` 中至多一条活跃记录。
- **API 契约（执法仪 + 手机 App 消费）**：
  - `POST /auth/device/bind/token` — 执法仪申请短期 token（或 GET 内嵌于状态接口）
  - `GET /auth/device/bind/status?device_id=&token=` — 轮询；返回 `pending` / `bound` / `expired` / `rejected`
  - `POST /auth/device/bind/confirm` — 手机 App 携带用户会话 + `device_id` + `token`；校验在岗、同公司、无冲突后写入 occupancy、发执法仪会话 token
  - `POST /auth/device/bind/release` — 解绑；结束 occupancy、写 history
  - `POST /auth/device/bind/shutdown` — 执法仪关机钩子；等同 release，reason=shutdown
- **手机 App API**（执法仪 + `mobile-app` 消费）：
  - `POST /auth/mobile/login` — 在岗人员人脸登录，签发 Bearer token
  - `POST /auth/mobile/register` — 自助建档进入在岗池，不绑定具体 `device_id`
  - `POST /auth/device/bind/confirm` — 手机扫码确认（Bearer + `device_id` + `token`）
- **废弃/降级**：执法仪不再调用 `face-only-login`、`/auth/patrol/register`（设备端路径）；演示账号 `POST /auth/login` 从执法仪移除。
- **绑定校验顺序**：token 有效 → 人员在岗 → `officer.company == recorder.company` → 人员无其他占用 → 设备无他人占用。

### 执法仪 Android

- **「我的」主页**：未绑定显示 QR（`https://<后端>/bind?device=DSJ-xxx&token=...`）+ 轮询；已绑定显示摘要卡片。
- **PersonnelInfoFragment**：未绑定空态 + 引导；已绑定只读 Hero + 信息行（保留现有布局风格）。
- **设置菜单**：保留人员信息、解绑、关于我们；删除息屏侧键、基础配置、注册、注销账号、退出登录、录像、隐私政策。
- **解绑**：调用 `bind/release`，清本机 `OfficerProfileStore` 与会话 token，回到 QR 态。
- **关机**：`BOOT_COMPLETED` / 关机广播或现有生命周期钩子检测整机重启，启动时若无上一次有效会话则清本地；并调用 `bind/shutdown` 结束云端占用（若上次未正常 release）。
- **移除**：`FaceVerifyActivity` 及 ML Kit 人脸入口、8 步注册向导、`SecuritySettingsFragment` 注销流、基础配置 Fragment、演示 relogin 路径。
- **配色**：设置子页改用与 `fragment_me` / `bg_home_screen` 一致的深色面板与浅色文字，不再使用 `#F2F3F5` 浅色管理风。

### 相册

- **数据源**：合并 `SessionManager` 现有 `AlbumItem`（拍照）与 `LoopRecordingStorage` / 本机 MP4 目录中的循环录像文件。
- **展示**：网格混排；照片沿用现有预览；录像点击进入播放（系统或内嵌 `VideoView`/`ExoPlayer` 择一，以实现简单为准）。
- **排序**：按文件修改时间倒序。
- **删除**：设置内录像菜单已删除；相册是否允许删除媒体 **本 PRD 不新增删除能力**（保持现有行为）。

### 手机 App（`mobile-app/`）

- **登录**：手机号 + 前置人脸 → `POST /auth/mobile/login`
- **注册**：表单 + 人脸 → `POST /auth/mobile/register`
- **扫码**：解析 QR `device` + `token` → 确认页 → `POST /auth/device/bind/confirm`
- **包名**：`com.aifieldcam.mobile`；默认后端与执法仪一致（`ApiConfig`）

### 领域术语同步

落地时更新 `CONTEXT.md`：公司设备池、扫码绑定、设备占用、解绑、使用历史等；修订待工/误报恢复中与「人脸登录」「8 步注册」的表述。

### 状态机（设备占用）

```
未占用 --[扫码确认成功]--> 已占用
已占用 --[解绑|关机]--> 未占用（写 history）
已占用 --[他人扫码]--> 拒绝
已占用(人A) --[人A在B机扫码且A已release]--> B已占用
```

## Testing Decisions

- **原则**：只测对外行为（API 响应、状态迁移、执法仪 UI 可见态），不测内部轮询间隔等实现细节。
- **后端（pytest）**：
  - token 过期与一次性消费
  - 同公司/跨公司绑定拒绝
  - 一机一人、一人一机冲突拒绝
  - release/shutdown 结束占用并保留 history
  - 在岗状态校验（status≠在岗拒绝）
- **Android（JUnit + 可选 UI）**：
  - 未绑定 Me 页显示 QR 态；mock bound 后显示摘要
  - 设置菜单项存在性（无注册/退出登录/录像等）
  - Album 聚合列表包含 photo + video 条目（可用 temp 目录 fixture）
- **先验**：参考现有 `patrol_store` / `officer_db` 测试与 `LoopRecordingStorageTest` 风格。

## Out of Scope

- 管理后台设备入库 UI（假定已有或后续 issue；本 PRD 只定义 `recorders.company` 契约）
- 执法仪端人脸采集/比对（`FaceVerifyActivity` 入口已移除；ML Kit 仍可能被手机端使用）
- 二维码被非 App 扫描的安全加固（深度链接鉴权以外的高级防重放）
- 相册云同步、跨设备查看媒体
- Web 大屏改造（继续使用现有 dashboard API，后续可消费 usage history）
- 隐私政策页面（删除，仅保留关于我们）

## Further Notes

- 报障/待工/误报恢复等业务流保留，但「恢复执勤」统一改为 **扫码绑定**，不再要求执法仪人脸登录。
- 侧键无障碍能力在 ROM/系统层保留，仅移除 App 设置内的「息屏侧键」跳转入口。
- 若关机钩子因厂商限制不可靠，备选方案为启动时比对 `last_shutdown_marker` 文件时间戳，PRD 实现阶段择可靠方案即可。
