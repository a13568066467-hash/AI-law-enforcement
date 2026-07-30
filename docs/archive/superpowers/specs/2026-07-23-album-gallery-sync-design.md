# 相册与系统相册双向删除同步 + 全屏预览

**日期:** 2026-07-23  
**状态:** 已批准并实现

## 背景

App 相册读应用私有目录主文件；保存时另复制到系统 MediaStore（`Pictures|Movies/AIFieldCam`）。两边互不感知删除，导致系统相册删了 App 仍显示。预览 Dialog 还带 AI 说明文案，不符合「全屏纯图」预期。

## 目标

1. App 长按进入多选 → 可全选/取消/批量删除；删除时同时清系统相册同名副本  
2. 系统相册删除 → 尽量实时删应用内对应主文件并刷新列表  
3. 点开照片：全屏黑底、无说明文案、保留关闭按钮  

## 方案

继续双份存储；用文件名（`DISPLAY_NAME`）联动删除；`ContentObserver` + 持久化快照做系统→App 同步。

## 行为

### App → 系统（多选批量删除）

- 长按进入多选并勾选该项；多选中点按切换勾选（不打开预览）  
- 底部条：全选 | 取消 | 删除(N)  
- 确认后逐项删主文件 + MediaStore 同名行 + 清内存列表  

### 系统 → App

- 观察 Images/Video MediaStore  
- 维护 AIFieldCam 下 displayName 快照（含进程重启后的持久化）  
- 快照中有、当前查询没有 → 按名删 `album/` 或 `videos/` 下同名文件  
- App 自身删除期间短暂抑制，避免重复处理  

### 预览

- 去掉 `tv_explain`；黑底全屏 `fitCenter`；保留关闭按钮与系统返回  

## 组件

| 单元 | 职责 |
|------|------|
| `GallerySaver` | 增补 `deleteImageFromGallery` |
| `AlbumMediaSync` | ContentObserver、快照、映射删除 |
| `SessionManager` | `deleteAlbumMedia` / 批量删除；启动挂 sync |
| `AlbumFragment` / `AlbumAdapter` | 多选模式 + 底部操作条 |
| `ImagePreviewDialogFragment` | 全屏无文案 |

## 验收

1. 长按进多选，可全选/取消勾选，批量删除后系统相册同名项消失  
2. 系统相册删 AIFieldCam 项后 App 相册尽快消失对应项  
3. 预览全屏无说明、有关闭按钮  
4. 从未进系统相册的文件仍可在 App 内删除  
