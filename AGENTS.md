# AGENTS.md · 空间场景（Spatial Scene for Android）

> 给后续接手的 AI/人类协作者的项目说明。**先读这个文件，再动代码。**

## 1. 项目目标

把一张普通 2D 照片，在**端侧离线**转成 2.5D 空间场景：设为动态壁纸后，随手机倾斜产生
**景深视差**（近处移动多、远处移动少），效果对标 iOS 26「空间场景」。

- 唯一真机目标：一加 13T / ColorOS 15 / Android 15 / 骁龙 8 Elite / 2640×1216 120Hz
- 当前阶段：自用 + 质量打磨；未来可能开源（已用 Apache-2.0）
- 语言：**界面与文档全部中文**

## 2. 硬约束（不可违反）

| 约束 | 说明 |
|---|---|
| 无 root、无 Shizuku | 纯第三方 `WallpaperService` 实现 |
| 全本地离线 | 不联网、不上传照片；模型打进 APK |
| **绝不露边** | 任何倾斜角度下画面边缘不得出现黑边/空白 |
| 报错必须上屏 | 异常信息直接显示在界面，并提供「复制错误信息」 |
| 纯云端构建 | 本地不装 Android SDK，全部走 GitHub Actions |
| 本地实验红线 | 只许 ≤512 小图 + 纯向量化（numpy/ORT），**禁止逐像素 Python 循环吃满内存** |
| 模型植入前 | 必须先本地做链路实验，再进 APK，省真机 debug 次数 |

## 3. 发布与版本

- `app/build.gradle.kts` 的 `versionCode` / `versionName` 每个版本 +1
- APK 文件名必须带版本号：`spatial-scene-v{ver}-debug.apk`
- `latest` Release：滚动，只保留最新 APK
- 里程碑：打 `v*` tag → CI 自动发 Release
- **签名固定**：`keystore/spatial-scene.p12`（alias/pass `spatialscene`），debug/release 共用；
  CI 用 apksigner 校验证书 SHA-256，不一致即失败。**不要重新生成 keystore**（用户会无法覆盖安装）
- 证书指纹：`44778f57b53a1a7a2f06b3eaef29936d8fe5e0292fd3be5cfc1c2bdb598c5f79`

## 4. 构建与 CI

- `.github/workflows/ci.yml`：拉模型（`scripts/fetch_models.sh`，含 sha256 校验）→
  `scripts/validate_model.py` 双模型自检（深度输出形状/范围；补全掩码语义 + 动态尺寸）→
  assembleDebug → 签名校验 → 上传 artifact / Release
- 本地**不要**尝试 gradle 构建（无 SDK）。验证靠：CI + 小图 Python/ORT 实验
- 下载页：https://github.com/DanXinG-bit/spatial-scene-android/releases/latest

## 5. 代码地图

```
app/src/main/java/com/danxing/spatialscene/
├── MainActivity.kt              Compose UI：照片 / 场景库 / 设置 三个页签
├── depth/
│   ├── DepthEstimator.kt        Depth Anything V2 Small（518 输入，fp16）
│   ├── DepthRefiner.kt          引导滤波：把 14× 下采样的深度贴到真实边缘（1024）
│   ├── SoftLayerDecomposer.kt   主体遮罩 + AI 补全背景 + 两层无悬崖深度
│   └── InpaintEstimator.kt      MI-GAN 补全（uint8 NCHW，≤1024）
├── scene/
│   ├── Scene.kt                 场景数据 + JSON 序列化（含 bg/fg 文件路径、aiFilled）
│   ├── SceneStore.kt            保存/删除/激活；写 image/depth/thumb/bg/bg_depth/fg_alpha/fg_depth
│   └── AppSettings.kt           偏好（视差强度、景深范围、灵敏度、渲染策略…）
├── gl/                          GL 基础：GlCore（着色器/纹理/网格）、PhotoProgram、ProceduralProgram
├── wallpaper/
│   ├── SpatialWallpaperService.kt  壁纸服务 + 引擎生命周期
│   ├── GlRenderThread.kt           EGL + 渲染循环：背景板 + 前景 alpha 双层合成
│   ├── GyroCamera.kt               姿态 → 相机偏移（屏幕法线）+ 静止回中
│   └── SceneShaders.kt             网格视差着色器
└── util/ImageLoader.kt          ImageDecoder 解码（ALLOCATOR_SOFTWARE，≤2048）
```

## 6. 关键技术约定（踩过的坑）

### 渲染
- 网格 192×192；顶点 `p += uCam * d * uParallax`（NDC，+x 右、+y 上）
- 照片纹理 **V 需翻转**；`uScale/uOffset` 做 cover + 用户缩放/平移
- **防止露边**：按**像素**统一两轴可用边距，`camY` 乘 `W/H` 补偿（横竖手感一致）
- **位移上限 = 屏宽 3%，且用 tanh 软压缩**（硬截断会让倾角一大就"撞墙"、手感变死）
- 软分层渲染：先画不透明背景板，再画前景（`fg_alpha`）——静止时合成结果 = 原图

### 陀螺仪（v0.9 起，已被用户验收方向正确）
```
n = R_refᵀ · z_cur          // 屏幕法线在参考姿态中的方向
camX = +k·n.x, camY = +k·n.y
```
- **不要**用 `(gx, gz)`：`gz` 偏差是 θ² 的二阶小量 → 上下方向会明显迟钝
- 语义：**画面朝被压下去（远离眼睛）的那一侧移动**
- 静止回中：静止 0.35s → 用 0.4s 一次连续 smoothstep 回正；回正期间忽略传感器；
  结束时 `R_ref = R_cur`（不跳变）。**不要**改成"边回边读传感器"，会变成一段一段

### 深度 / 补全
- 深度模型输入 `pixel_values` [1,3,H,W] float32，H/W 为 14 的倍数，长边 518
- 输出归一化 [0,1]（1=近）；`DepthRefiner` 升到长边 1024（引导滤波 radius 8, eps 0.01）
- MI-GAN：`image` uint8 [1,3,H,W] + `mask` uint8 [1,1,H,W]，**255=已知，0=待补**
- ORT Java：`createTensor(env, ByteBuffer, shape)` 会把 uint8 当 int8 →
  **必须用 4 参重载 + `OnnxJavaType.UINT8`**
- **MI-GAN 输出是 NCHW（平面）**：R 平面 / G 平面 / B 平面依次排列（已实测验证）。
  按 RGB 交错读取会造成背景"灰白 + 横向错位 + 整块复制"。读输出前先用
  `TensorInfo.shape` 判断 `shape[1]==3`(NCHW) 还是 `shape[3]==3`(NHWC)
- `pushPull()` 内部按 `idx*3` 寻址（RGB 交错）。**单通道（深度）必须先 `pushPullGray()` 打包成 3 通道**，
  否则必崩 `ArrayIndexOutOfBoundsException: length=n; index=n+…`（v0.6~v0.9.0 一直踩这个坑）
- 分层参数（v0.9.4 起）：
  - 前景 alpha **不羽化**（羽化带会把两层不同位移混合 → "液态玻璃"重影）
  - AI 只补**环带**：`band = mask ∧ ¬erode(mask, 6% 长边)`，细长主体自动退化为整块
  - 背景层颜色：整块脚印先做 push-pull 预填补（给模型"背景状"上下文），AI 只补环带
  - 背景/前景层**深度**用 `nearestFill`（由外向内最近值，平坦）而不是 push-pull
    （碗形深度 = 透镜效应，背景直线会被弯）
- `Bitmap.getPixels` 前确保位图是软件位图且尺寸与目标一致（`Canvas.drawBitmap` 画到新位图最稳）

## 7. 提交与验收

- 一个功能点/修复点 = 一个 commit，信息写清"改了什么 + 为什么"
- 用户验收后才算完成；**质量未过关前不做边缘功能**（如实时预览）
- 用户反馈渠道：手机截图（QQ 路径 / 剪贴板 `pi-clipboard-*.png`）
- 用户核心验收项：
  1. 倾斜方向符合"压哪边、画面往哪边移"
  2. 「角度转移」测试（手机绕圈倾斜）四方向手感一致
  3. 静止后干脆回正（一次，不拖沓）
  4. 主体移动时露出的是 **AI 补全的背景**，不是被"吞掉"或拉伸
  5. 场景库徽章正确显示 AI / 几何 / 单层 + 诊断信息

## 8. 当前状态（v0.9.1）

- 已完成：M0 程序化山谷、M1 深度估计、M2 场景库/精修/UI、M5 软分层、M6 MI-GAN 补全
- 已修复：签名冲突、陀螺仪映射（方向+线性度）、回中、**pushPull 单通道崩溃**
- 待验证：v0.9.1 是否首次真正跑通软分层（徽章应显示「AI 软分层（主体 + 补全背景）」）

## 9. 路线图（按优先级）

1. **P0** 软分层真正跑通 + 质量验收（AI 背景是否自然、主体边缘是否干净）
2. **P1 预览页**（GLSurfaceView + 陀螺仪/自动摆动 + 滑条实时生效）：
   - 双指捏合框选最终展示范围（决定视差安全边距）
   - **把「设置」页的内容并入预览**（视差强度/景深范围/灵敏度/缩放/位置），
     设置页只保留全局项；三页签简化为「照片 / 场景库」+ 预览内设置
3. **P2 质量**：u2netp 分割模型替代 Otsu 遮罩；深度分块（2×2 重叠 + 线性对齐）
   或换 Depth Anything V2 Base；补全提高分辨率
4. **P3 多层 LDI（K=4）**：每层在"它后面"被 AI 补全（用户提出的 n-1 层扩图思路）
5. 其他：NNAPI 加速对比、HDR/ProXDR 兼容

## 10. 本地环境

- Windows + Git Bash；代理 `http://127.0.0.1:7897`（gh/curl/pip 需要）
- `MSYS_NO_PATHCONV=1` 处理 openssl subject 等路径；`/tmp` 与 `C:/tmp` 不是同一池
- Python 3.9 + onnxruntime 1.19.2（仅做小图实验）
