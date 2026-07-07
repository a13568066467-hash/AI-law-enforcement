# DSJ-ZECN6A1 存储与 TF 卡

> 规格书：3GB+32GB 内置 + **TF 卡支持**  
> 实现：`MediaStorageLocator.kt`、`PhoneCameraHelper.kt`

---

## 1. 为什么「SanDisk 用不了」

| 现象 | 原因 |
|------|------|
| 系统设置里能看到 TF 卡 | 卡已挂载，如 `/storage/49A5-0AFB`（约 21GB） |
| App 仍写满内置 | **旧代码**固定用 `filesDir`（内置 userdata 仅 ~3.6GB） |
| `installDebug` 失败 | 内置满，与 TF 卡是否插入无关 |

**不是硬件读不到卡**，是 App 未路由到 TF 卡。

---

## 2. 现行策略（已实现）

执法仪本机（`DeviceProfile.isDsjZecn6a1`）：

1. 扫描 `Context.getExternalFilesDirs()` 所有卷  
2. **优先可移除 TF 卡**（路径非 `/emulated/`）  
3. 同类型卷中选 **剩余空间最大** 的一个  
4. 录像/拍照/录音落盘路径：  
   - 视频：`…/Android/data/com.aifieldcam.app/files/Movies/AIFieldCam/videos/`  
   - 照片：`…/Pictures/AIFieldCam/album/`  
5. 系统相册（MediaStore）写入 **同一 TF 卷**，不再只写内置 primary  

启动时 logcat 标签 `AiFieldCam` / `MediaStorage` 会打印当前选用路径。

---

## 3. 用户侧检查

1. **设置 → 存储**：确认 TF 卡已挂载、非「仅充电」  
2. 首次插入建议在系统里 **格式化 / 挂载** 为 portable 存储  
3. 拔卡前 **先停录**，避免写入中断  
4. 电脑拷素材：连接 USB → `Android/data/com.aifieldcam.app/files/Movies/AIFieldCam/videos/`

---

## 4. 内置 8GB vs adb 看到 3.6G

ROM 分区后 **userdata 给 App 的约 3.6GB**；其余为系统/vendor/product。  
32GB 规格中另有 **TF 扩展**，长跑 1080p 必须依赖 TF 卡。

---

## 5. 后续可增强（未做）

- 设置页展示「当前存储：TF 卡 / 内置 · 剩余 xx GB」  
- 内置 &lt; 200MB 时自动删除最旧 N 个本地录像  
- 分段录像（单文件上限）对齐 18h 规格  
