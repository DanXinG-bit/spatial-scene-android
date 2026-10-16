#!/usr/bin/env python3
"""CI 模型自检：用 ONNX Runtime CPU（与 Android 端同一套内核）实际跑一遍模型。

历史教训：
1. onnx-community 的 int8 量化版包含 ConvInteger 算子，ORT CPU/NNAPI 均无实现，
   直到装上手机才暴露；
2. 张量形状写反（[1,3,W,H] vs [1,3,H,W]）元素数相同，不报错但结果全错。

此脚本会在构建阶段拦截这类问题，并按模型类型做针对性检查：
- 深度模型：输出应为 [1,H,W]，数值范围合理；
- 补全模型（MI-GAN）：uint8 输入 + 动态尺寸 + **掩码语义**（255=已知区域必须被保留）。
"""
import sys

import numpy as np
import onnxruntime as ort


def validate_depth(session: ort.InferenceSession) -> None:
    input_name = session.get_inputs()[0].name
    # 140 = 14 * 10，满足 Depth Anything V2 对 14 倍数的要求
    dummy = np.random.rand(1, 3, 140, 140).astype(np.float32)
    output = session.run(None, {input_name: dummy})[0]

    if output.shape != (1, 140, 140):
        print(f"[深度] 输出形状异常: {output.shape}")
        sys.exit(1)

    print(
        f"[深度] OK: input={input_name} | output={output.shape} "
        f"| range=[{output.min():.3f}, {output.max():.3f}]"
    )


def validate_inpaint(session: ort.InferenceSession) -> None:
    inputs = session.get_inputs()
    image_name = None
    mask_name = None
    for item in inputs:
        # 形状为动态符号时 shape[1] 可能是字符串，用类型 + 名称兜底
        channels = item.shape[1] if len(item.shape) == 4 else None
        if channels == 3 or (isinstance(channels, str) and "image" in item.name):
            image_name = item.name
        elif channels == 1 or "mask" in item.name:
            mask_name = item.name
    if image_name is None or mask_name is None:
        print(f"[补全] 无法识别输入: {[(i.name, i.shape, i.type) for i in inputs]}")
        sys.exit(1)

    for (height, width) in [(192, 256), (168, 224)]:
        rng = np.random.default_rng(0)
        image = (rng.random((1, 3, height, width)) * 255).astype(np.uint8)
        mask = np.full((1, 1, height, width), 255, np.uint8)
        mask[:, :, height // 4:height // 2, width // 4:width // 2] = 0  # 0 = 待补全

        output = session.run(None, {image_name: image, mask_name: mask})[0]
        if output.shape != (1, 3, height, width):
            print(f"[补全] 输出形状异常: {output.shape} (期望 {(1, 3, height, width)})")
            sys.exit(1)

        # 已知区域必须被保留（掩码语义检查：若 255/0 反了，这里会失败）
        known = mask[0, 0] == 255
        diff = np.abs(output[0].astype(np.int16) - image[0].astype(np.int16))
        known_error = diff[:, known].mean()
        hole_std = output[0][:, ~known].std()
        if known_error > 2.0:
            print(f"[补全] 已知区域被改动，平均误差 {known_error:.2f}（掩码语义可能反了）")
            sys.exit(1)
        if hole_std < 1.0:
            print(f"[补全] 待补全区域没有生成内容（std={hole_std:.2f}）")
            sys.exit(1)

        print(
            f"[补全] OK: {width}x{height} | 已知区误差={known_error:.2f} "
            f"| 生成区 std={hole_std:.1f} | 输出范围=[{output.min()}, {output.max()}]"
        )

    print("[补全] 动态尺寸 OK")


def main() -> None:
    if len(sys.argv) < 2:
        print("用法: validate_model.py <model.onnx>")
        sys.exit(2)

    path = sys.argv[1]
    session = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    print(f"模型: {path}")
    for item in session.get_inputs():
        print(f"  输入 {item.name} {item.shape} {item.type}")

    if len(session.get_inputs()) >= 2:
        validate_inpaint(session)
    else:
        validate_depth(session)


if __name__ == "__main__":
    main()
