# 赢筑 AI 执法记录仪

> **DSJ-ZECN6A1 本机 Android 主控 App** + 云端 AI + 巡查员认证

## 快速导航

| 目的 | 入口 |
|------|------|
| **文档索引** | [`docs/README.md`](docs/README.md) |
| **产品方案** | [`docs/product/产品设计方案.md`](docs/product/产品设计方案.md) |
| **Android App** | [`android-app/`](android-app/) |
| **云端后端** | [`backend/README.md`](backend/README.md) |
| **执法仪硬件** | [`docs/hardware/DSJ-ZECN6A1硬件参数.txt`](docs/hardware/DSJ-ZECN6A1硬件参数.txt) |

## 标准项目结构

```
text1/
├── README.md
├── docs/
├── backend/          # FastAPI + MySQL 巡查员库 + AI 代理
├── android-app/      # DSJ-ZECN6A1 主控 App（Kotlin）
└── tools/tests/      # 主机侧测试脚本
```

## 常用命令

```bat
REM 后端
cd backend
venv\Scripts\activate
uvicorn app.main:app --host 0.0.0.0 --port 8000

REM Android
cd android-app
gradlew.bat installDebug

REM 测试
python tools/tests/app_api_test.py
python backend/tests/test_me_menu_flow.py
```

## 验收要点

- [ ] DSJ 本机 Camera2 录像 / 拍照 / 物理按键
- [ ] 巡查员 8 步注册 + 人脸登录
- [ ] 云端 AI 对话与识图
- [ ] ZE69 录像灯 / 光感夜视（需系统签名写 sysfs）
