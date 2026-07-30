# 赢筑AI

> **DSJ-ZECN6A1 本机 Android 主控 App** + **手机扫码 App** + 云端 AI + MySQL 巡查员库

## 快速导航

| 目的 | 入口 |
|------|------|
| **文档索引** | [`docs/README.md`](docs/README.md) |
| **产品方案** | [`docs/产品需求与交/产品需求.md`](docs/产品需求与交/产品需求.md) |
| **执法仪 App** | [`android-app/`](android-app/) |
| **手机 App** | [`mobile-app/`](mobile-app/) |
| **云端后端** | [`backend/README.md`](backend/README.md) |
| **执法仪刷机/预装** | [`docs/DSJ-ZECN6A1 执法仪/ZE69刷机与预装.md`](docs/DSJ-ZECN6A1%20执法仪/ZE69刷机与预装.md) |
| **领域词** | [`CONTEXT.md`](CONTEXT.md) |

## 标准项目结构

```
text1/
├── README.md
├── CONTEXT.md        # 领域术语（扫码绑定、设备池等）
├── docs/
├── backend/          # FastAPI + MySQL + 设备绑定会话 + AI 代理
├── android-app/      # DSJ-ZECN6A1 执法仪主控 App（Kotlin）
├── mobile-app/       # 手机扫码绑定 App（Kotlin）
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

仓库根目录或 `backend` 下任选其一：

```bat
backend\run.bat
```

```bat
cd backend
run.bat
```

- 健康检查：<http://127.0.0.1:8000/health>（应见 `officer_db_ok: true`、`face_engine` 等）
- 局域网真机/执法仪 App 填 `http://电脑IP:8000`，不能用 `127.0.0.1`
- 等价手动命令：`venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload`（无需先 `activate`）

### Android（开发机 debug 包）

```bat
cd android-app
copy local.properties.example local.properties
gradlew.bat installDebug
```

`local.properties` 里配置 `sdk.dir`；可选 `backend.host` 作为默认后端地址。

### 手机 App（扫码绑定）

```bat
cd mobile-app
copy local.properties.example local.properties
gradlew.bat installDebug
```

与执法仪同网段访问后端；登录/注册后人脸比对，扫描执法仪「我的」页二维码完成绑定。详见 [`docs/特性 PRD/qr-scan-device-login.md`](docs/特性%20PRD/qr-scan-device-login.md)。

### Android（执法仪 platform 签 release）

见 [`docs/DSJ-ZECN6A1 执法仪/ZE69刷机与预装.md`](docs/DSJ-ZECN6A1%20执法仪/ZE69刷机与预装.md)：`assembleRelease` → `scripts\sign-platform.ps1` → adb 安装或预装。

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
- [ ] 扫码绑定：执法仪出码 + `mobile-app` 确认 → 设备显示人员摘要
- [ ] 手机端登录/注册（`POST /auth/mobile/login|register`）
- [ ] 云端 AI 对话与识图
- [ ] ZE69 录像灯 / 光感夜视

