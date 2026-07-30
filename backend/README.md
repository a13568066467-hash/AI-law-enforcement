# AI Field Cam — 云端后端

> 登录、Agent A/B 对话、识图 Vision、全双工实时语音。App 只调本服务，不持百炼 API Key。

## 目录结构

按业务域分包，域内轻量 MVC（`router` / `service` / `repository` / `schemas`）。**请使用域路径 import**（如 `app.officers.repository`、`app.device_bind.service`）；根目录旧平铺模块名已删除。

```
backend/
├── app/
│   ├── main.py              # 组装入口：迁移 + include_router + /health
│   ├── core/                # 跨域鉴权、配置
│   ├── db/
│   │   ├── connection.py    # SQLite/MySQL 连接与查询辅助
│   │   └── migrations/      # officers/recorders/device_bind/field_events ensure_schema
│   ├── officers/            # 人员档案、演示/手机登录
│   ├── device_bind/         # 扫码占用绑定
│   ├── recorders/           # 设备台账
│   ├── command_call/        # 指挥连线 / 监看 / UserSig / MQTT
│   ├── ai/                  # chat / vision / video / realtime / agents
│   ├── field_events/        # 现场事件工单
│   ├── dashboard/           # 大屏 API
│   ├── webrtc/              # 旧 HTTP 信令
│   └── patrol/              # 历史 patrol API
├── requirements.txt
├── .env.example
└── README.md
```

## 快速启动

首次：

```bat
cd backend
python -m venv venv
venv\Scripts\python.exe -m pip install -r requirements.txt
copy .env.example .env
```

日常（一条命令）：

```bat
run.bat
```

- 健康检查：<http://127.0.0.1:8000/health>
- 无 `DASHSCOPE_API_KEY` 时自动 mock，便于联调 BLE

## 演示登录

| 手机号 | 密码 |
|--------|------|
| 13800000000 | demo |

## API

全量接口见 [`docs/开发指南/API接口.md`](../docs/开发指南/API接口.md)。Agent 行为见 [`docs/开发指南/云端AI代理.md`](../docs/开发指南/云端AI代理.md)。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/auth/login` | 演示登录，返回 `token` |
| POST | `/auth/device/bind/*` | 扫码绑定 token / 状态 / 确认 / 解绑 / 关机 |
| POST | `/auth/mobile/login` | 手机端人脸登录 |
| POST | `/auth/mobile/register` | 手机端自助注册（在岗池） |
| POST | `/auth/patrol/*` | 历史 8 步注册 / 人脸 API（执法仪已弃用） |
| POST | `/v1/chat` | Agent A/B + `ble_cmds` |
| POST | `/v1/vision` | JPEG base64 → 识图说明 |
| POST | `/v1/video` | 抽帧 JPEG 列表 → 视频分析 |
| WebSocket | `/v1/realtime/voice` | 16kHz PCM 上行、24kHz 模型语音下行及白名单工具调用 |
| GET/POST | `/v1/webrtc/*` | 视频连线 HTTP 信令中继 |

## App 配置

Android App 设置页配置 `API_BASE_URL`（如 `http://电脑局域网IP:8000`）。真机不能用 `127.0.0.1`。

## 环境变量

| 变量 | 说明 |
|------|------|
| `DASHSCOPE_API_KEY` | 百炼 Key；不设则 mock |
| `DASHSCOPE_WORKSPACE_ID` | 百炼业务空间 ID；实时语音必填 |
| `DASHSCOPE_REALTIME_REGION` | 实时语音地域，默认 `cn-beijing` |
| `REALTIME_MODEL` | 默认 `qwen3.5-omni-flash-realtime` |
| `REALTIME_VOICE` | 模型音色，默认 `Tina` |
| `CHAT_MODEL` | 默认 `qwen-turbo` |
| `VISION_MODEL` | 默认 `agnes-2.0-flash` |
| `OFFICER_DB_DRIVER` | `sqlite`（默认）或 `mysql` |
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_USER` / `MYSQL_PASSWORD` / `MYSQL_DATABASE` | MySQL 连接（设 `OFFICER_DB_DRIVER=mysql` 时） |

实时语音不提供无 Key mock。缺少 Workspace 或密钥时，WebSocket 会返回
`configuration_error`；鉴权失败会以关闭码 `4401` 拒绝连接。Android 会清理采音和播放状态，
使用本机 TTS 提示后在下一次 PTT 自动重试。

## 巡查员档案数据库

默认使用 **SQLite** 文件 `backend/data/officers.db`。表结构与权威源分层见 [`docs/架构与数据/协议/数据库设计.md`](../docs/架构与数据/协议/数据库设计.md)。

### 改用 MySQL

1. 安装依赖：`pip install -r requirements.txt`（含 `pymysql`）
2. 在 MySQL 中执行 `scripts/init_mysql.sql`，或让后端启动时自动建表
3. 在 `.env` 中配置：

```env
OFFICER_DB_DRIVER=mysql
MYSQL_HOST=127.0.0.1
MYSQL_PORT=3306
MYSQL_USER=root
MYSQL_PASSWORD=你的密码
MYSQL_DATABASE=aifieldcam
```

4. 重启 uvicorn，访问 `/health` 应看到 `"officer_db": "mysql://..."` 且 `"officer_db_ok": true`

可用 **Navicat / DBeaver / MySQL Workbench** 连接同一套 `MYSQL_*` 参数查看 `officers`、`auth_tokens` 表。
