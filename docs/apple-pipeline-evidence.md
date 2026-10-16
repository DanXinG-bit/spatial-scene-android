# 苹果空间场景：证据、论文与我们的方案对照

> 本文把「有据可查的事实」与「合理推测」严格分开，避免再拍脑袋。

## 一、确认的事实（有出处）

| 事实 | 出处 |
|---|---|
| iOS 26「空间场景」在 **神经网络引擎 (ANE)** 上运行，从平面图像重建深度信息 | 苹果对外回应（ITHome / 新浪科技转载，2025-11） |
| 空间场景会「**分离图层**」 | 同上 |
| iPhone 12 及以后机型均可使用（不需要 Apple Intelligence 云端） | 同上 |
| 「空间照片」是**双目立体对**封装进 HEIC（含基线/视场角/视差元数据），路径是拍摄而非算法合成 | WWDC24 Session 10166 |
| 苹果有端侧**全景分割 + 深度**研究积累（人像模式、舞台光等） | Apple ML Research: On-device Panoptic Segmentation |
| 手机单屏的立体感来自**运动视差**（陀螺仪驱动虚拟视点），不是双目视差 | 掘金《从 WWDC26 空间重构再看端侧 2D 转 3D》2026-06 |
| 行业共识管线 = 单目深度估计 + **分层表示** + **遮挡区补全** + 实时重投影 | 同上；Facebook One Shot 3D Photography (TOG 2020) |
| 2D 转 3D 的真正难点是「**主体背后是什么**」：该区域必须被推断出来 | 同上 |
| **硬分层（hard layering）无法建模头发这类精细结构**，会产生截断感 | **SLIDE** (ICCV 2021) 论文摘要，Google Research |
| 软分层 + 深度感知补全（可纯几何实现）是当时的 SOTA，处理 672×1008 图仅 0.07s（不含抠图） | SLIDE 论文 §4 Runtime |
| 分层表示的两条主线：**LDI**（沿视线存多层像素）与 **MPI**（32 层固定深度平面 RGBA） | SLIDE 论文 §1/§2；LDI 原始论文 SIGGRAPH'98 |

**尚未公开（我们只能推断）**：

- 苹果空间场景的确切网络结构、是否使用学习式补全、是否用 MPI/高斯泼溅；
- 苹果的处理耗时较长，与「深度 + 补全（可能含神经补全）+ 高分辨率」是自洽的，但无直接证据。

## 二、SLIDE 的核心公式（我们照抄的部分）

1. **前景软可见度**：`A = exp(−β‖∇D‖²)`
   - 深度平坦处 `A≈1`（显示原图）；深度悬崖处 `A≈0`（透明，露出背景板）。
   - 这一步直接消灭「网格在悬崖处被撑开」的波浪纹——不是切开图片，而是让悬崖"透过去"。
2. **软遮挡/去遮挡图**（我们暂未实现）：沿水平/垂直扫描线比较视差差，估计「哪些背景像素会被露出来」，用于指导补全。
3. **深度感知补全**：SLIDE 用神经网络补全（训练时用遮挡掩码 + 随机笔刷），并要求补全结果**主要从更远的区域借内容**，避免把前景"复制"到背景。
4. **合成**：`I* = A·I_FG + (1−A)·I_BG`，两层都使用**连续深度**（不是常数平面）。

## 三、我们的实现路线（对照）

| 阶段 | 做法 | 结果 | 结论 |
|---|---|---|---|
| M2 | 连续网格 + 完整深度 | 悬崖处网格拉伸 → **波浪纹** | 与 SLIDE 论文描述的失败模式一致 |
| M3/M4 | 硬分层（2层→4层刚性卡片） | 出现**断层/空白/黑边**，像"抠下来平移" | 正是论文批评的 hard layering |
| **M5（当前）** | **软可见度 A + 窄带补全 + 连续深度双 layer 合成** | 待实测 | 与 SLIDE 主路线一致 |
| M6（可选） | 学习式补全（LaMa/MAT 等 ONNX 小模型）替换几何 push-pull | 质量↑、耗时↑、体积↑ | 对应"苹果耗时更长"的可能解释 |

## 四、关键参考文献

- **SLIDE**: Jampani et al., *Single Image 3D Photography with Soft Layering and Depth-aware Inpainting*, ICCV 2021（Google Research）
- **3D-Photo**: Shih et al., *3D Photography using Context-aware Layered Depth Inpainting*, CVPR 2020
- **One Shot 3D Photography**: Kopf et al., TOG 2020（Facebook）
- **3D Ken Burns**: Niklaus et al., TOG 2019
- **LDI**: Shade et al., *Layered Depth Images*, SIGGRAPH 1998
- **Depth Anything V2**: Yang et al., 2024（我们使用的深度模型）
- 掘金：《从 WWDC26 空间重构(Spatial Reframing)再看端侧 2D 转 3D 的技术演进》，2026-06
