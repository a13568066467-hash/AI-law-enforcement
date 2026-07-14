# DSJ-ZECN6A1 存储与 TF 卡

> 规格书：3GB+32GB 内置 + **TF 卡支持**  
> 实现：`MediaStorageLocator.kt`、`PhoneCameraHelper.kt`、`RecordingPipelineWatchdog.kt`、`StorageRetentionWatchdog.kt`

---

## 1. 卷类型说明（易混淆）

| 路径示例 | 实际含义 | App 标签 |
|----------|----------|----------|
| `/storage/2394-1112/...` | 用户插入的 **SanDisk 便携式 SD**（~30GB） | 「SD卡」等 |
| `/storage/49A5-0AFB/...` | 出厂 **eMMC vfat 公共分区**（~21GB），**不是** TF 卡 | 「Rom」/「内置媒体分区」 |
| `/storage/emulated/0/...` | 内置 userdata（~3.6GB，易满） | 「内置」 |
| `/mnt/expand/{uuid}/...` | adoptable 扩展存储（部分固件/App 不可写） | 「SanDisk SD卡」 |

**常见误判**：系统设置里能看到 `/storage/49A5-0AFB`，看起来像「外置卡」，实为 Rom 分区。长跑 1080p 应使用 **SanDisk 便携式 SD**（格式化时选「便携式存储」）。

---

## 2. 现行路由策略（`MediaStorageLocator`）

执法仪本机（`DeviceProfile.isDsjZecn6a1`）按 **评分** 选卷，而非简单「第一个外置路径」：

1. 扫描 `Context.getExternalFilesDirs()` + `/mnt/expand/{uuid}` 候选  
2. **优先** SanDisk / 便携式 SD（路径 `/storage/<uuid>/`，非 `emulated`、非 `49A5-0AFB`）  
3. **其次** adoptable `/mnt/expand/`（须 probe 可写且剩余 ≥1GB）  
4. **再次** 出厂 eMMC vfat `49A5-0AFB`（SanDisk 不可用时的回退，~21GB）  
5. **最后** 内置 `filesDir` / emulated（~3.6GB，仅兜底）

同卷原则：录像、拍照、录音、`MediaStore` 写入 **同一卷**。

落盘路径：

| 类型 | 路径 |
|------|------|
| 视频 | `…/Android/data/com.aifieldcam.app/files/Movies/AIFieldCam/videos/` |
| 照片 | `…/Pictures/AIFieldCam/album/` |
| 录音 | `…/Movies/AIFieldCam/audio/` |

启动时 logcat 标签 `MediaStorage` 打印选用路径与可用 MB，例如：

`media storage -> SD卡 · /storage/2394-1112/... · 可用 30427MB`

---

## 3. 录像与空间管理

### 3.1 循环录像（帧级无缝分片 + 自动覆盖）

| 参数 | 值 | 说明 |
|------|-----|------|
| 模式 | **循环录像** | `DeviceProfile.CONTINUOUS_LOOP_RECORDING` |
| 分片阈值 | **1 GB** | `RecordingSegmentPolicy.MAX_SEGMENT_BYTES` |
| 换片 | **Session 内热换 muxer** | `MediaEncoderPipeline.rotateSegmentBlocking`；相机/编码器不重启 |
| 覆盖 | `LoopRecordingStorage.ensureSpaceForNextSegment` | 开录/换片前删最旧 MP4（含相册），直至可写下一片 + 256MB |
| 辅助 | `StorageRetentionWatchdog` 85%→50% | 与循环覆盖并存 |
| 体验 | 红灯不灭、无 Toast/TTS | 每片独立 MP4 |
| 编码 | H.264 + AAC 管线 | 每片有声 MP4；换片时伴随音不断；PTT 旁路共麦 |
| 停止 | 用户停录、换片前无法腾出空间 | 循环模式下不因 1GB 剩余空间单独停录 |

**回退路径**（关闭 `CONTINUOUS_LOOP_RECORDING`）：MediaRecorder + 停 Session 续录，片间约 3–5s 空档。

### 3.2 停录兜底（`RecordingPipelineWatchdog`）

录像中每 **5s** tick，约每 **50s** 检查一次剩余空间：

| 阈值 | 行为 |
|------|------|
| 剩余 **≤ 1GB** | 自动停录，TTS「存储空间不足，录像已自动保存」 |
| 剩余 **≤ 2GB** | 仅 WARN 日志（`storageWarned`） |

### 3.3 轮询删除旧录像（`StorageRetentionWatchdog`）

**仅在**「正在录像 **且** 前台服务持有计数 > 0」时，每 **60s** 检查卷 **使用率**：

| 参数 | 值 |
|------|-----|
| 触发 | 卷使用率 **≥ 85%** |
| 停止删除 | 使用率 **< 50%** |
| 删除顺序 | `videos/` 下 `*.mp4`，按 `lastModified` **从旧到新** |
| 保护 | 跳过 `NativeRecorder.currentOutputFile()`（正在写入的文件） |
| 相册 | **一并删除** MediaStore 同名副本（`GallerySaver.deleteVideoFromGallery`） |
| `important` 标记 | **不保护**，照样删 |
| 用户通知 | **无** Toast/TTS，仅 logcat `StorageRetention` |

与 1GB 停录兜底 **并存**：尽量在 85% 时回收空间，避免拖到 1GB 才停录。

---

## 4. 用户侧检查

1. **设置 → 存储**：SanDisk 应显示为 SD/外置存储；若只有 ~21GB「Rom」类分区，可能是 eMMC 公共区而非 SanDisk  
2. 新卡建议格式化为 **便携式存储**（本机 adoptable 卷 App 可能不可写）  
3. 拔卡前 **先停录**  
4. 电脑拷素材：USB → `Android/data/com.aifieldcam.app/files/Movies/AIFieldCam/videos/`  
5. 验证 App 是否用上 SD：看 logcat `MediaStorage` 或路径是否含 `/storage/<非49A5-0AFB>/`

---

## 5. 内置 8GB vs adb 看到 3.6G

ROM 分区后 **userdata 给 App 约 3.6GB**；其余为 system/vendor/product。  
32GB 规格中的 **TF 扩展** 才是长跑 1080p 的主存储；`49A5-0AFB` 为 eMMC 上的额外可写分区，不能替代 SanDisk。

---

## 6. 后续可增强（未做）

- 设置页展示「当前存储：SD 卡 / Rom / 内置 · 剩余 xx GB · 使用率 xx%」  
- 非录像时段的低空间清理策略（当前仅录像中轮询）
