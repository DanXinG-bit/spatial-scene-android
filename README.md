# 空间场景 · Spatial Scene for Android

把 iOS 26 的「空间场景」体验带到 Android：

> 选一张照片 → 端侧 AI 估计深度 → 生成 2.5D 场景 → 设为动态壁纸，倾斜手机即可看到景深视差。

本项目完全离线运行，不需要 root，所有构建在 GitHub Actions 云端完成（本地无需安装 Android SDK）。

## 当前状态

- [x] **M0 地基**：动态壁纸服务 + OpenGL ES 3.0 深度网格渲染 + 陀螺仪相机 + 云端 CI
- [x] **M1**：端侧深度模型（Depth Anything V2 Small, fp16）接入照片，可查看深度图与耗时
- [x] **M2**：照片 + 深度网格真实接入动态壁纸；场景库（保存/删除/切换）；强度/景深/灵敏度/缩放滑条；引导滤波边缘精修；按需渲染省电
- [ ] **M2.1**：应用内实时预览、手感微调
- [ ] **M3**：场景编辑与壁纸应用引导打磨
- [ ] **M4**：多层（LDI/MPI）高质量补全

## 下载体验

- 最新构建（自动更新，debug 签名）：<https://github.com/DanXinG-bit/spatial-scene-android/releases/tag/latest>

## 构建

本项目通过 GitHub Actions 构建，无需本地 Android 工具链。

- 每次 push 到 `main`：Actions 产出 `app-debug.apk`（可在 Actions 页面下载）
- 手动触发：Actions → CI → Run workflow

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 | Kotlin + Jetpack Compose (Material 3) |
| 渲染 | OpenGL ES 3.0（WallpaperService + EGL 渲染线程） |
| 深度模型 | Depth Anything V2 Small（ONNX，端侧推理，Apache-2.0） |
| 构建 | Gradle 8.11.1 + AGP 8.7.3 + JDK 17，GitHub Actions |

## 目录结构

```
app/                       # 应用与动态壁纸
  src/main/java/com/danxing/spatialscene/
    MainActivity.kt        # 首页（设置壁纸入口）
    depth/                 # 端侧深度估计（ONNX Runtime）
    wallpaper/             # WallpaperService + GL 渲染 + 陀螺仪相机
    ui/theme/              # Material 3 主题
docs/                      # 工程文档
.github/workflows/ci.yml   # 云端构建
```

## 文档

- [模型选择与踩坑记录](docs/model-notes.md)（为什么用 fp16、int8 的 ConvInteger 坑）

## 路线图

详细研究与规划见：`pi play/苹果空间场景的研究与复刻/`（本地研究目录）。

## 许可证

Apache License 2.0（详见 [LICENSE](LICENSE)）。
