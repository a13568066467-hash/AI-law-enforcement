# 完整 AI 功能 — 开发路线与验收

> **DSJ-ZECN6A1 本机 App → 云端 AI**  
> 更新：2026-06

---

## 1. 端到端数据流

```
物理按键 / App 按钮
    → DSJ 本机（Camera2 / 麦克风）
    → 主控 App（SessionManager + HTTPS）
    → 云端 backend（Agent A/B + Vision + 巡查员库）
    → App 展示 / 相册 / 录像列表
```

---

## 2. 两块并行开发

| 块 | 目录 | 必达 |
|----|------|------|
| **云端** | `backend/` | 登录、巡查员、`/v1/chat`、`/v1/vision` |
| **App** | `android-app/` | Camera2、按键、相册、AI 页、我的 |

---

## 3. 启动顺序

### ① 云端

```bash
cd backend
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

### ② 安装 App 到执法仪

```bat
cd android-app
gradlew.bat installDebug
```

### ③ 联调

1. 我的 → 人员信息 → 完成注册  
2. 首页 → 本机拍照 / 录像  
3. AI 助手 → 文字对话  
4. 相册 → 查看识图结果  

---

## 4. 验收清单

| # | 功能 | 验收 |
|---|------|------|
| 1 | 本机录像 | Camera2 1080p 可录可播 |
| 2 | 本机拍照 | 相册出现 JPEG |
| 3 | 识图 | 登录后显示云端说明 |
| 4 | 对话控设备 | 「开始录像」执行成功 |
| 5 | 追问识图 | Agent B 回答 |
| 6 | 扫码绑定 | 执法仪出码 + 手机确认 → 人员摘要显示 |
