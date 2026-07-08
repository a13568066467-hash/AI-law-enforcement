# 赢筑AI

> **DSJ-ZECN6A1 本机 Android 主控 App** + 云端 AI + 巡查员认证

## 快速导航

| 目的 | 入口 |
|------|------|
| **文档索引** | [`docs/README.md`](docs/README.md) |
| **产品方案** | [`docs/product/产品需求.md`](docs/product/产品需求.md) |
| **Android App** | [`android-app/`](android-app/) |
| **云端后端** | [`backend/README.md`](backend/README.md) |
| **执法仪刷机/预装** | [`docs/hardware/ZE69刷机与预装.md`](docs/hardware/ZE69刷机与预装.md) |
| **执法仪硬件** | [`docs/hardware/DSJ-ZECN6A1硬件参数.txt`](docs/hardware/DSJ-ZECN6A1硬件参数.txt) |

## 标准项目结构

```
text1/
├── README.md
├── docs/
├── backend/          # FastAPI + MySQL 巡查员库 + AI 代理 + 人脸比对
├── android-app/      # DSJ-ZECN6A1 主控 App（Kotlin）
└── tools/tests/      # 主机侧测试脚本
```

## 常用命令

### 后端（首次）

在仓库根目录执行：

```bat
cd backend
python -m venv venv
venv\Scripts\activate
pip install -r requirements.txt
copy .env.example .env
```

编辑 `backend\.env`：至少配置 MySQL（`OFFICER_DB_DRIVER=mysql` 与 `MYSQL_*`）；可选百炼 `DASHSCOPE_API_KEY`（不设则 AI mock）。

### 后端（日常启动）

```bat
cd backend
venv\Scripts\activate
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

- 健康检查：<http://127.0.0.1:8000/health>（应见 `officer_db_ok: true`、`face_engine` 等）
- 局域网真机/执法仪 App 填 `http://电脑IP:8000`，不能用 `127.0.0.1`

### Android（开发机 debug 包）

```bat
cd android-app
copy local.properties.example local.properties
gradlew.bat installDebug
```

`local.properties` 里配置 `sdk.dir`；可选 `backend.host` 作为默认后端地址。

### Android（执法仪 platform 签 release）

见 [`docs/hardware/ZE69刷机与预装.md`](docs/hardware/ZE69刷机与预装.md)：`assembleRelease` → `scripts\sign-platform.ps1` → adb 安装或预装。

### 测试（先启动后端）

在**仓库根目录**：

```bat
python tools\tests\app_api_test.py
cd backend
venv\Scripts\activate
python -m pytest tests\ -v
```

`test_me_menu_flow.py` 需 MySQL 与 `backend\.env` 已配置；`app_api_test.py` 默认打 `http://127.0.0.1:8000`。

## 验收要点

- [ ] DSJ 本机 Camera2 录像 / 拍照 / 物理按键
- [ ] 巡查员 8 步注册 + 人脸登录（本机 `DSJ-` 编号与云端绑定）
- [ ] 云端 AI 对话与识图
- [ ] ZE69 录像灯 / 光感夜视

