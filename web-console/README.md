# 指挥连线 Web 控制台

Vite + React + `trtc-sdk-v5`。首期仅指挥连线：选设备、发起/结束、远端视频、常开麦。

## 运行

```bash
cp .env.example .env
# 按需改 VITE_API_BASE
npm install
npm run dev
```

后端需已配置 `TRTC_SDK_APP_ID` / `TRTC_SECRET_KEY`，并有已占用设备；设备侧安装含真 TRTC 适配器的 APK 且 HTTP poll 可达。

## 构建

```bash
npm run build
```
