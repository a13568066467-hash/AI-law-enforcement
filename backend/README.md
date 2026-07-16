# AI Field Cam — 云端后端

> 登录、Agent A/B 对话、识图 Vision。App 只调本服务，不持百炼 API Key。

## 目录结构

```
backend/
├── app/
│   ├── main.py           # FastAPI 入口
│   ├── agents.py         # Agent A/B + Vision
│   └── session_store.py  # 会话内存
├── requirements.txt
├── .env.example
└── README.md
```

## 快速启动

```bash
cd backend
python -m venv venv
venv\Scripts\activate          # Windows
pip install -r requirements.txt
copy .env.example .env           # 填入 DASHSCOPE_API_KEY（可选）
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

- 健康检查：<http://127.0.0.1:8000/health>
- 无 `DASHSCOPE_API_KEY` 时自动 mock，便于联调 BLE

## 演示登录

| 手机号 | 密码 |
|--------|------|
| 13800000000 | demo |

## API

全量接口见 [`docs/guides/API接口.md`](../docs/guides/API接口.md)。Agent 行为见 [`docs/guides/云端AI代理.md`](../docs/guides/云端AI代理.md)。

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
| GET/POST | `/v1/webrtc/*` | 视频连线 HTTP 信令中继 |

## App 配置

Android App 设置页配置 `API_BASE_URL`（如 `http://电脑局域网IP:8000`）。真机不能用 `127.0.0.1`。

## 环境变量

| 变量 | 说明 |
|------|------|
| `DASHSCOPE_API_KEY` | 百炼 Key；不设则 mock |
| `CHAT_MODEL` | 默认 `qwen-turbo` |
| `VISION_MODEL` | 默认 `agnes-2.0-flash` |
| `OFFICER_DB_DRIVER` | `sqlite`（默认）或 `mysql` |
| `MYSQL_HOST` / `MYSQL_PORT` / `MYSQL_USER` / `MYSQL_PASSWORD` / `MYSQL_DATABASE` | MySQL 连接（设 `OFFICER_DB_DRIVER=mysql` 时） |

## 巡查员档案数据库

默认使用 **SQLite** 文件 `backend/data/officers.db`。表结构与权威源分层见 [`docs/architecture/数据库设计.md`](../docs/architecture/数据库设计.md)。

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
