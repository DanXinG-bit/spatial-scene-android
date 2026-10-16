# 模型选择与踩坑记录

## 当前选择

| 项目 | 值 |
|---|---|
| 模型 | Depth Anything V2 Small（ONNX, fp16 权重） |
| 来源 | `onnx-community/depth-anything-v2-small` → `onnx/model_fp16.onnx` |
| 大小 / sha256 | 49.6MB / `2df6223f206b5164e21f664ace61dabeb9bb6a49b8b5a3e00510b4807d0f5b04` |
| 输入 | `pixel_values` float32 `[1, 3, H, W]`，H/W 为 14 的倍数（本项目用 518 或按长边缩放） |
| 输出 | `predicted_depth` float32 `[1, H, W]`（近处数值大） |
| 推理 | ONNX Runtime Mobile 1.20.0，CPU/XNNPACK（NNAPI 默认关闭，见下） |
| 许可证 | Apache-2.0（Small 版本；Base/Large 为 CC-BY-NC 不可商用） |

## 踩坑：int8/quantized 版本不可用（2026-10-16）

- 现象：手机上选图后报错
  `ORT_NOT_IMPLEMENTED: Could not find an implementation for ConvInteger(10) node ... '/patch_embed/proj/Conv_quant'`
- 原因：`onnx-community` 的 `model_int8.onnx` / `model_quantized.onnx` 使用动态量化产生的 `ConvInteger` 算子，
  ORT 的 CPU 与 NNAPI 内核均未实现该算子。桌面 Python `onnxruntime` 可 100% 复现。
- 处理：
  1. 切换到 `model_fp16.onnx`（普通 Conv，CPU 可跑）；
  2. 新增 `scripts/validate_model.py`，CI 在打包前用 CPU 实跑一遍模型，拦截不支持的算子；
  3. 保留错误信息在界面直接展示 + 一键复制，便于手机端定位问题。

## NNAPI 说明

- NNAPI（高通 Hexagon）理论上更快，但不同机型的算子支持差异大。
- 目前 `DepthEstimator.TRY_NNAPI = false`（走 CPU/XNNPACK，稳定优先）。
- M2 阶段会做 NNAPI 开关与性能对比，仍需保证失败可回退、不影响出图。
