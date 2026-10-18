# tools/sim · Windows 桌面模拟器

算法实验用工具（**不打包进 APK**）。在 Windows 上直接跑：
深度 → 精修 → 分层 → MI-GAN 补全 → 视差渲染，用 WASD 模拟陀螺仪倾斜。

目的：把"真机测一轮 3~5 分钟"缩短到"本地 3 秒 + 键盘倾斜"，
并且可以直接把结果 PNG 交给协作者看（不用再截屏手机）。

## 依赖

```bash
# 模型（约 75MB，首次需要）
bash scripts/fetch_models.sh

# Python 依赖（本机已有：numpy / onnxruntime / opencv-python / pillow）
pip install numpy onnxruntime opencv-python
```

## 用法

```bash
# 交互窗口：WASD 倾斜 / QE 缩放 / R 回中
# 1=合成 2=深度 3=主体遮罩 4=背景板 5=AI洞 6=遮罩叠加 / P 保存截图 / ESC 退出
python tools/sim/sim.py --image path/to/photo.jpg

# 只导出调试图（无窗口，适合 CI / 远程查看）
python tools/sim/sim.py --image photo.jpg --pack 768 --dump out/

# 跳过 AI（只测几何/遮罩）
python tools/sim/sim.py --image photo.jpg --no-ai
```

`--dump` 会输出 7 张：
`01_composite 02_depth 03_mask 04_bg_ai 05_hole 06_overlay 07_composite_tilt`

命令行会打印耗时与 **AI 改动**（洞内 AI 相对预填补的平均差异，0~255）。
`AI 改动 < 2` 基本等于"AI 没在工作"。

## 与 Kotlin 实现的一致性

参数与 `app/src/main/java/com/danxing/spatialscene/` 保持一致：

| 项 | 值 |
|---|---|
| 深度输入 | 长边 518、14 的倍数、ImageNet 均值方差、1%~99% 稳健归一化 |
| 精修 | 引导滤波 r=8, eps=0.01，长边 1024 |
| 分层层数 | 2（主体 + 背景板），背景深度压缩 ×0.3 |
| 遮罩 | Otsu → 连通块 0.2% → 闭运算 2% 长边 → 洞填充 |
| 补全洞 | 主体脚印（dilate 2px）+ 几何预填补上下文 |
| 位移上限 | 屏宽 3%，tanh 软压缩 |

**注意**：这是第二份实现，只用于算法研究。改动验证后要同步回 Kotlin（并更新本文件与 AGENTS.md）。
