# Album Gallery Sync Implementation Plan

> **For agentic workers:** Implement task-by-task from the approved spec.

**Goal:** Bidirectional delete sync between app album and system gallery; fullscreen photo preview without captions.

**Architecture:** Keep dual storage; link deletes by `DISPLAY_NAME`; `AlbumMediaSync` ContentObserver + persisted snapshot; `SessionManager.deleteAlbumMedia` as single delete API.

**Tech Stack:** Kotlin, Android MediaStore, ContentObserver, DialogFragment

## Global Constraints

- Match MediaStore copies under `Pictures/AIFieldCam` and `Movies/AIFieldCam` only
- Do not delete app-only files that were never in the snapshot
- Chinese UI strings for delete confirm

## Tasks

- [x] Spec written
- [x] GallerySaver + SessionManager delete API
- [x] AlbumMediaSync
- [x] Album long-press UI
- [x] Fullscreen preview without caption
- [x] Build verify + commit
