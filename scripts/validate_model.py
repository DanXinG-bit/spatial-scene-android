#!/usr/bin/env python3
"""CI 模型自检：用 ONNX Runtime CPU（与 Android 端同一套内核）实际跑一遍模型。

历史教训：onnx-community 的 int8 量化版包含 ConvInteger 算子，
ORT CPU/NNAPI 均无实现，直到装上手机才暴露。此脚本会在构建阶段拦截这类问题。
"""
import sys

import numpy as np
import onnxruntime as ort


def main() -> None:
    if len(sys.argv) < 2:
        print("用法: validate_model.py <model.onnx>")
        sys.exit(2)

    path = sys.argv[1]
    session = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name

    # 140 = 14 * 10，满足 Depth Anything V2 对 14 倍数的要求
    dummy = np.random.rand(1, 3, 140, 140).astype(np.float32)
    output = session.run(None, {input_name: dummy})[0]

    if output.shape != (1, 140, 140):
        print(f"输出形状异常: {output.shape}")
        sys.exit(1)

    print(
        f"模型 OK: {path} | input={input_name} | output={output.shape} "
        f"| range=[{output.min():.3f}, {output.max():.3f}]"
    )


if __name__ == "__main__":
    main()
