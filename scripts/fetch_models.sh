#!/usr/bin/env bash
# 下载端侧模型到 assets。构建机/CI 使用；本机开发时也可手动执行。
#
# 两个模型：
#   1. depth_anything_v2_small_fp16.onnx —— 单目深度估计
#   2. migan_pipeline_v2.onnx            —— 图像补全（MI-GAN，MIT，Places2 训练，含预处理/后处理管线）
#
# 注意：不要换用 int8/quantized 版本！其 ConvInteger 算子在 ONNX Runtime
# 的 CPU/NNAPI 内核中没有实现（见 scripts/validate_model.py 的 CI 自检）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/models"
mkdir -p "$DEST"

fetch() {
  local name="$1" url="$2" sha="$3"
  if [ -f "$DEST/$name" ]; then
    echo "已存在: $name"
  else
    echo "下载 $name …"
    curl -sSL --retry 3 --retry-delay 2 -o "$DEST/$name" "$url"
  fi
  echo "校验 sha256 …"
  echo "$sha  $DEST/$name" | sha256sum -c -
  echo "  $(du -h "$DEST/$name" | cut -f1)"
}

fetch "depth_anything_v2_small_fp16.onnx" \
  "${DEPTH_URL:-https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model_fp16.onnx}" \
  "2df6223f206b5164e21f664ace61dabeb9bb6a49b8b5a3e00510b4807d0f5b04"

fetch "migan_pipeline_v2.onnx" \
  "${MIGAN_URL:-https://huggingface.co/andraniksargsyan/migan/resolve/main/migan_pipeline_v2.onnx}" \
  "6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b"

echo "全部完成"

