# Backend app 按业务域 + 域内 MVC 重组

**日期:** 2026-07-28  
**状态:** 已批准（待实现计划）

## 背景

`backend/app` 约 20 个平铺模块：`main.py` 同时承担路由、鉴权与库初始化；各域 `*_db` / `*_store` 与 `CREATE`/`_migrate` 混在同文件，缺少统一迁移目录，不利于查阅与增改。

## 目标

1. 按**业务域**分包，域内采用轻量 MVC（`router` / `service` / `repository` + `schemas`）
2. 数据库迁移集中到 `app/db/migrations/`
3. `main.py` 只负责组装（配置加载、迁移、`include_router`）
4. **不改对外 URL 与业务语义**；便于后续单域新增/修改

## 非目标

- 引入 Alembic / 换 ORM（首期仍用现有 SQL + 驱动）
- 重写业务逻辑或改 API 契约
- 一次 PR 拆完所有巨型文件的内部细节（可分批：先搬迁再加深切分）

## 目标目录

```
backend/app/
├── main.py                 # 组装入口
├── core/                   # 跨域：配置、鉴权依赖、共享工具
│   ├── __init__.py
│   ├── config.py           # 从 env 读取（可渐进从 main 抽出）
│   └── auth.py             # Bearer / token 校验等
├── db/
│   ├── __init__.py
│   ├── connection.py       # 连接/驱动公共能力（渐进从 officer_db 抽）
│   └── migrations/
│       ├── __init__.py     # run_all_migrations()
│       ├── officers.py
│       ├── recorders.py
│       ├── device_bind.py
│       └── field_event_tickets.py
├── officers/               # 人员档案、手机登录/注册相关
├── device_bind/            # 扫码占用绑定
├── recorders/              # 设备台账
├── command_call/           # 指挥连线 / 监看 / UserSig / MQTT
├── ai/                     # chat / vision / video / realtime / agents / session / demo / expert
├── field_events/           # 现场事件工单
├── dashboard/              # Web 控制台 / 大屏 API
├── webrtc/                 # 旧 HTTP 信令（与 command_call 边界写清，暂不合并）
└── patrol/                 # 历史 patrol API（若仍保留）
```

各业务域包内约定：

| 文件 | MVC 角色 | 职责 |
|------|----------|------|
| `router.py` | View | HTTP/WS 路由、入参校验、调用 service |
| `service.py` | Controller | 业务规则与编排 |
| `repository.py` | Model 访问 | 落库/查询（原 `*_db` / store 的数据面） |
| `schemas.py` | DTO | Pydantic 请求/响应（可从 main 逐步迁入） |
| `__init__.py` | — | 导出 `router` 供 main 挂载 |

体量小的域允许首期 `service.py` 与 `repository.py` 暂合并，但**目录与命名仍按上表预留**，避免再次平铺。

## 域 ↔ 现有文件映射

| 目标域 | 现有主要文件 |
|--------|----------------|
| `officers/` | `officer_db.py`（人员 CRUD/鉴权相关业务面）、手机登录注册路由（现 `main.py` 片段） |
| `device_bind/` | `device_bind_db.py`, `device_bind_store.py` + bind 路由 |
| `recorders/` | `recorder_db.py` + recorder 路由 |
| `command_call/` | `command_call_session.py`, `command_call_mqtt.py`, `usersig.py` + 相关路由 |
| `ai/` | `agents.py`, `realtime_voice.py`, `session_store.py`, `demo_scenarios.py`, `expert.py` + chat/vision/video/realtime 路由 |
| `field_events/` | `field_event_ticket_db.py`, `field_event_ticket_store.py` + 工单路由 |
| `dashboard/` | `dashboard_api.py` |
| `webrtc/` | `webrtc_signaling.py` + `/v1/webrtc/*` |
| `patrol/` | `patrol_store.py`, `patrol_verify.py`, `face_engine.py`, `face_yolo.py` + `/auth/patrol/*` |
| `db/migrations/` | 各 `*_db.py` 内 `CREATE TABLE` / `_migrate*` |
| `core/` | `main.py` 中鉴权辅助、共享常量（渐进） |

## 迁移策略

1. 每个表族一份 migration 模块，导出 `ensure_schema(conn)` 或等价函数  
2. `db/migrations/__init__.py` 提供 `run_all_migrations()`，顺序显式固定（officers → recorders → device_bind → field_events → …）  
3. 启动路径：`main` 启动时调用 `run_all_migrations()`，替代分散的 `officer_db.init_db()` 等；或 `init_db` 变为对 `run_all_migrations` 的薄封装以保持兼容  
4. **首期不改变表结构**，只搬家

## main.py 形态

```python
# 示意
from app.db.migrations import run_all_migrations
from app.device_bind.router import router as device_bind_router
# ...

run_all_migrations()
app.include_router(device_bind_router)
# ...
```

WebSocket（realtime）可仍挂在 `ai/router.py` 或 `ai/realtime_router.py`，由 main 挂载。

## 兼容与测试

- 对外路径、状态码、JSON 字段不变  
- 单元测试改 import 到新路径；允许短暂保留 `app.device_bind_store` 等 **re-export 垫片**（标 deprecated），下一迭代删除  
- 每迁完一个域：跑该域相关 `backend/tests/test_*.py` + 冒烟 `/health`

## 分批落地顺序（建议）

1. 建 `core/`、`db/migrations/` 骨架 + `run_all_migrations` 接通启动  
2. `device_bind`（边界清晰、测试已有）  
3. `field_events`  
4. `command_call` + `usersig`  
5. `recorders` / `officers`（含从 officer_db 抽迁移）  
6. `ai` / `dashboard` / `webrtc` / `patrol`  
7. 删垫片、更新 `backend/README.md` 目录说明  

## 决策记录

- 选型：**业务域分包 + 域内 MVC**（非全局横切 models/views）  
- 迁移集中到 `db/migrations/`，暂不引入 Alembic  
- 保持 API 契约与表结构不变，优先可回滚的搬迁
