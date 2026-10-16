#!/usr/bin/env bash
# 下载端侧深度模型（Depth Anything V2 Small, fp16）到 assets。
# 构建机/CI 使用；本机开发时也可手动执行。
#
# 注意：不要换用 int8/quantized 版本！其 ConvInteger 算子在 ONNX Runtime
# 的 CPU/NNAPI 内核中没有实现（见 scripts/validate_model.py 的 CI 自检）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/models"
MODEL_NAME="depth_anything_v2_small_fp16.onnx"
MODEL_URL="${MODEL_URL:-https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model_fp16.onnx}"
MODEL_SHA256="2df6223f206b5164e21f664ace61dabeb9bb6a49b8b5a3e00510b4807d0f5b04"

mkdir -p "$DEST"

if [ -f "$DEST/$MODEL_NAME" ]; then
  echo "模型已存在: $DEST/$MODEL_NAME"
  exit 0
fi

echo "下载深度模型…"
curl -sSL --retry 3 --retry-delay 2 -o "$DEST/$MODEL_NAME" "$MODEL_URL"

echo "校验 sha256…"
echo "$MODEL_SHA256  $DEST/$MODEL_NAME" | sha256sum -c -

echo "完成: $(du -h "$DEST/$MODEL_NAME" | cut -f1)"
