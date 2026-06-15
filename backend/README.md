# AI Field Cam — 云端后端

> 完整 AI 功能的「大脑」：登录、Agent A/B 对话、识图 Vision。  
> App 只调本服务，**不持百炼 API Key**。

## 快速启动

```bash
cd backend
python -m venv venv
venv\Scripts\activate          # Windows
pip install -r requirements.txt
copy .env.example .env           # 填入 DASHSCOPE_API_KEY（可选）
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

- 健康检查：<http://127.0.0.1:8000/health>
- 无 `DASHSCOPE_API_KEY` 时自动 **mock** 对话与识图，便于联调 BLE

## 演示登录

| 手机号 | 密码 |
|--------|------|
| 13800000000 | demo |

与 App 设置页「模拟登录」一致。

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/auth/login` | 返回 `token` |
| POST | `/v1/chat` | Agent A/B + `ble_cmds` |
| POST | `/v1/vision` | JPEG base64 → 识图说明 |

详见 [`docs/云端AI代理.md`](../docs/云端AI代理.md)。

## App 配置

`apptext/common/config.uts`：

```ts
export const API_BASE_URL = 'http://你的电脑IP:8000'
```

真机调试不能用 `127.0.0.1`，需局域网 IP。

## 环境变量

| 变量 | 说明 |
|------|------|
| `DASHSCOPE_API_KEY` | 百炼 Key；不设则 mock |
| `CHAT_MODEL` | 默认 `qwen-turbo` |
| `VISION_MODEL` | 默认 `qwen3-vl-8b-instruct` |
