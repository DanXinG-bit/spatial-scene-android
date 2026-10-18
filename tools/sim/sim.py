"""
Windows 端「空间场景」模拟器（算法实验用，不是发布物）。

为什么存在：Android 上一轮验证要 3~5 分钟，这个工具在 Windows 上直接跑
深度 / 分层 / 补全 / 视差渲染，WASD 模拟陀螺仪倾斜，1~6 切换调试视图。

它必须与 Kotlin 实现**同一套数学**：
  - 深度：长边 518、14 的倍数、ImageNet 均值方差、1%~99% 稳健归一化
  - 精修：引导滤波（radius 8, eps 0.01）到 1024
  - 分层：Otsu → 连通块 → 闭运算(2%) → 洞填充 → 环带/脚印 → push-pull 预填补
          → MI-GAN 补全 → 柔化 → 背景/前景深度（nearest fill）
  - 渲染：逐像素位移，位移上限 = 屏宽 3% 且 tanh 软压缩

用法：
  python sim.py --image photo.jpg                # 交互窗口
  python sim.py --image photo.jpg --dump out/    # 只导出调试图（无需窗口）
  python sim.py --image photo.jpg --no-ai        # 跳过 MI-GAN

按键：WASD 倾斜 / QE 缩放 / R 回中 / 1=合成 2=深度 3=主体遮罩 4=背景板 5=洞
      6=遮罩叠加 / S 保存当前视图 / ESC 退出
"""

import argparse
import os
import sys
import time

import cv2
import numpy as np
import onnxruntime as ort

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
MODEL_DIR = os.path.join(REPO, 'app', 'src', 'main', 'assets', 'models')
DEPTH_MODEL = os.path.join(MODEL_DIR, 'depth_anything_v2_small_fp16.onnx')
MIGAN_MODEL = os.path.join(MODEL_DIR, 'migan_pipeline_v2.onnx')

# 与 Kotlin 一致的参数
PACK_LONG_SIDE = 1024
TARGET_LONG_SIDE = 518
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)
BG_DEPTH_SCALE = 0.3
CLOSE_FRACTION = 0.02
SHIFT_CAP_FRACTION = 0.03


# --------------------------------------------------------------------- 模型
def load_depth():
    if not os.path.exists(DEPTH_MODEL):
        sys.exit(f'缺少深度模型：{DEPTH_MODEL}\n请先运行 scripts/fetch_models.sh')
    sess = ort.InferenceSession(DEPTH_MODEL, providers=['CPUExecutionProvider'])
    return sess, sess.get_inputs()[0].name


def load_migan():
    if not os.path.exists(MIGAN_MODEL):
        return None
    sess = ort.InferenceSession(MIGAN_MODEL, providers=['CPUExecutionProvider'])
    names = {i.name: i for i in sess.get_inputs()}
    img = next((n for n in names if 'image' in n), list(names)[0])
    msk = next((n for n in names if 'mask' in n), list(names)[1])
    return sess, img, msk


# ------------------------------------------------------------------ 深度估计
def robust_normalize(values):
    lo, hi = np.percentile(values, 1), np.percentile(values, 99)
    if hi - lo < 1e-6:
        return np.full_like(values, 0.5)
    return np.clip((values - lo) / (hi - lo), 0, 1).astype(np.float32)


def estimate_depth(sess, name, bgr):
    h, w = bgr.shape[:2]
    scale = TARGET_LONG_SIDE / max(h, w)
    nw = max(14, int(round(w * scale / 14)) * 14)
    nh = max(14, int(round(h * scale / 14)) * 14)
    small = cv2.resize(bgr, (nw, nh), interpolation=cv2.INTER_AREA)
    rgb = cv2.cvtColor(small, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
    x = ((rgb - MEAN) / STD).transpose(2, 0, 1)[None]
    out = sess.run(None, {name: x})[0]
    depth = np.squeeze(out).astype(np.float32)
    depth = robust_normalize(depth)
    if depth.shape[:2] != (h, w):
        depth = cv2.resize(depth, (w, h), interpolation=cv2.INTER_LINEAR)
    return depth


# -------------------------------------------------------------- 引导滤波精修
def box(img, r):
    return cv2.blur(img, (2 * r + 1, 2 * r + 1), borderType=cv2.BORDER_REFLECT)


def guided_filter(guide, src, r=8, eps=0.01):
    """单通道引导滤波（guide、src 均 0..1）。"""
    g = guide.astype(np.float32)
    p = src.astype(np.float32)
    mean_i = box(g, r)
    mean_p = box(p, r)
    corr_i = box(g * g, r)
    corr_ip = box(g * p, r)
    var_i = corr_i - mean_i * mean_i
    cov_ip = corr_ip - mean_i * mean_p
    a = cov_ip / (var_i + eps)
    b = mean_p - a * mean_i
    return box(a, r) * g + box(b, r)


def refine_depth(depth, bgr, alpha_r=8, eps=0.01):
    h, w = bgr.shape[:2]
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY).astype(np.float32) / 255.0
    return np.clip(guided_filter(gray, depth, alpha_r, eps), 0, 1).astype(np.float32)


# ------------------------------------------------------------------ 几何工具
def largest_components(mask, min_size):
    n, labels, stats, _ = cv2.connectedComponentsWithStats(mask.astype(np.uint8), 8)
    out = np.zeros_like(mask, dtype=np.uint8)
    for i in range(1, n):
        if stats[i, cv2.CC_STAT_AREA] >= min_size:
            out[labels == i] = 1
    return out.astype(bool)


def fill_enclosed(mask):
    """不与画面边缘连通的背景块 -> 并入主体。"""
    bg = (~mask).astype(np.uint8)
    n, labels = cv2.connectedComponents(bg, connectivity=4)
    reachable = np.zeros(n, dtype=bool)
    for lab in np.unique(np.concatenate([
        labels[0, :], labels[-1, :], labels[:, 0], labels[:, -1]
    ])):
        if lab > 0:
            reachable[lab] = True
    bad = [lab for lab in range(1, n) if not reachable[lab]]
    fill = np.isin(labels, bad) if bad else np.zeros_like(mask, dtype=bool)
    return mask | fill


def push_pull(color, valid):
    """多尺度加权补全（RGB 0..1）。color: HxWx3, valid: HxW (1=有效)。"""
    cols = color.astype(np.float32).copy()
    wts = valid.astype(np.float32).copy()
    levels = [(cols, wts)]
    while cols.shape[0] > 1 or cols.shape[1] > 1:
        h, w = cols.shape[:2]
        nh, nw = max(1, (h + 1) // 2), max(1, (w + 1) // 2)
        c = cv2.resize(cols * wts[..., None], (nw, nh), interpolation=cv2.INTER_AREA)
        wk = cv2.resize(wts, (nw, nh), interpolation=cv2.INTER_AREA)
        safe = np.maximum(wk, 1e-6)
        cols = c / safe[..., None]
        wts = wk
        levels.append((cols, wts))
    # 最粗层若全空，直接返回输入
    if levels[-1][1].max() <= 0:
        return color.copy()
    for i in range(len(levels) - 2, -1, -1):
        fc, fw = levels[i]
        cc, cw = levels[i + 1]
        h, w = fw.shape
        up_c = cv2.resize(cc, (w, h), interpolation=cv2.INTER_LINEAR)
        up_w = cv2.resize(cw, (w, h), interpolation=cv2.INTER_LINEAR)
        t = np.clip(fw, 0, 1)[..., None]
        fc[:] = fc * t + up_c * (1 - t)
        fw[:] = 1.0
    return levels[0][0]


def nearest_fill(src, valid, max_steps=400):
    """洞内取最近有效像素的值（平坦外延，避免碗形深度）。"""
    out = src.astype(np.float32).copy()
    known = valid.astype(bool).copy()
    if known.all():
        return out
    for _ in range(max_steps):
        if known.all():
            break
        acc = np.zeros_like(out)
        cnt = np.zeros(out.shape, dtype=np.float32)
        for dy, dx in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            k = np.roll(known, (dy, dx), (0, 1))
            v = np.roll(out, (dy, dx), (0, 1))
            if dy == -1:
                k[-1, :] = False
            elif dy == 1:
                k[0, :] = False
            if dx == -1:
                k[:, -1] = False
            elif dx == 1:
                k[:, 0] = False
            acc += v * k
            cnt += k
        new = (~known) & (cnt > 0)
        if not new.any():
            break
        out[new] = acc[new] / cnt[new]
        known |= new
    return out


# ------------------------------------------------------------------ 分层
def decompose(bgr, depth, migan, use_ai=True):
    h, w = bgr.shape[:2]
    long_side = max(h, w)
    scale = min(1.0, PACK_LONG_SIDE / long_side)
    if scale < 1.0:
        bgr = cv2.resize(bgr, (max(8, int(round(w * scale))), max(8, int(round(h * scale)))),
                         interpolation=cv2.INTER_AREA)
        depth = cv2.resize(depth, (bgr.shape[1], bgr.shape[0]), interpolation=cv2.INTER_LINEAR)
    h, w = bgr.shape[:2]
    n = h * w

    d = cv2.blur(depth.astype(np.float32), (3, 3))
    d8 = (d * 255).astype(np.uint8)
    t, _ = cv2.threshold(d8, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
    mask = largest_components(d8 > t, int(n * 0.002))
    if mask.mean() < 0.03 or mask.mean() > 0.90:
        cut = np.percentile(d, 70)
        mask = largest_components(d > cut, int(n * 0.002))

    r = int(np.clip(round(CLOSE_FRACTION * max(h, w)), 4, 32))
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * r + 1, 2 * r + 1))
    closed = cv2.morphologyEx(mask.astype(np.uint8), cv2.MORPH_CLOSE, kernel).astype(bool)
    mask = fill_enclosed(closed)

    alpha = mask.astype(np.float32)
    footprint = cv2.dilate(mask.astype(np.uint8), np.ones((5, 5), np.uint8)).astype(bool)
    valid_bg = (~footprint).astype(np.float32)

    colors = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB).astype(np.float32) / 255.0
    fill = push_pull(colors.copy(), valid_bg)
    ai_delta = 0.0
    if use_ai and migan is not None and footprint.any():
        sess, in_img, in_mask = migan
        img_u8 = (np.clip(fill, 0, 1) * 255).astype(np.uint8).transpose(2, 0, 1)[None]
        mask_u8 = np.where(footprint, 0, 255).astype(np.uint8)[None, None]
        out = sess.run(None, {in_img: img_u8, in_mask: mask_u8})[0]
        planar = out.shape[1] == 3
        rgb = out[0].transpose(1, 2, 0) if planar else out[0]
        ai = rgb.astype(np.float32) / 255.0
        ai_delta = float(np.abs(ai - fill)[footprint].mean() * 255) if footprint.any() else 0.0
        fill = ai

    softened = cv2.blur(fill, (5, 5))
    bg_color = fill.copy()
    bg_color[footprint] = fill[footprint] * 0.35 + softened[footprint] * 0.65
    bg_color[~footprint] = colors[~footprint]

    bg_depth = nearest_fill(d, valid_bg, max_steps=48) * BG_DEPTH_SCALE
    fg_depth = nearest_fill(d, mask, max_steps=48)

    return {
        'image': colors,
        'depth': d.astype(np.float32),
        'mask': mask,
        'alpha': alpha,
        'bg_color': bg_color.astype(np.float32),
        'bg_depth': bg_depth.astype(np.float32),
        'fg_depth': fg_depth.astype(np.float32),
        'hole': footprint,
        'ai_delta': ai_delta,
    }


# ------------------------------------------------------------------ 渲染
def ndc_to_uv(ndc_x, ndc_y, sx, sy, ox, oy):
    return (ndc_x - ox) / sx * 0.5 + 0.5, (ndc_y - oy) / sy * 0.5 + 0.5


def sample(tex, u, v, border=cv2.BORDER_REPLICATE):
    """u,v: 0..1（v 向上）. 返回双线性采样结果。"""
    h, w = tex.shape[:2]
    mx = (u * (w - 1)).astype(np.float32)
    my = ((1.0 - v) * (h - 1)).astype(np.float32)
    return cv2.remap(tex, mx, my, cv2.INTER_LINEAR, borderMode=border)


def render(layers, cam_x, cam_y, zoom=1.25, parallax=1.0, gamma=1.0):
    colors, depth, alpha = layers['image'], layers['depth'], layers['alpha']
    bgc, bgd = layers['bg_color'], layers['bg_depth']
    fgd = layers['fg_depth']
    h, w = depth.shape[:2]

    # cover 缩放
    screen_aspect = w / h
    image_aspect = w / h
    sx, sy = 1.0, 1.0
    if image_aspect >= screen_aspect:
        sx = image_aspect / screen_aspect
    else:
        sy = screen_aspect / image_aspect
    sx *= zoom
    sy *= zoom

    # tanh 软压缩 + 3% 屏宽上限
    limit = min(0.9 * min((sx - 1) * w * 0.5, (sy - 1) * h * 0.5), SHIFT_CAP_FRACTION * w)
    par = max(parallax, 0.05)
    raw_x = cam_x * par * w * 0.5
    raw_y = cam_y * (w / h) * par * h * 0.5
    shift_x = float(limit * np.tanh(raw_x / limit)) if limit > 1 else 0.0
    shift_y = float(limit * np.tanh(raw_y / limit)) if limit > 1 else 0.0
    cx = shift_x / (w * 0.5) / par
    cy = shift_y / (h * 0.5) / par

    xs = np.linspace(-1, 1, w, dtype=np.float32)[None, :].repeat(h, 0)
    ys = np.linspace(1, -1, h, dtype=np.float32)[:, None].repeat(w, 1)

    def warp(color, dmap, a=None):
        u0, v0 = ndc_to_uv(xs, ys, sx, sy, 0.0, 0.0)
        d = sample(dmap, np.clip(u0, 0, 1), np.clip(v0, 0, 1))
        d = np.clip(d, 0, 1) ** gamma
        u, v = ndc_to_uv(xs - cx * d * par, ys - cy * d * par, sx, sy, 0.0, 0.0)
        u = np.clip(u, 0, 1)
        v = np.clip(v, 0, 1)
        return sample(color, u, v), (sample(a, u, v) if a is not None else None)

    bg, _ = warp(bgc, bgd)
    fg, fa = warp(colors, fgd, alpha)
    out = fg * fa[..., None] + bg * (1 - fa[..., None])
    return np.clip(out, 0, 1), (shift_x, shift_y)


# ------------------------------------------------------------------ 主程序
def to_bgr(rgb):
    return cv2.cvtColor((np.clip(rgb, 0, 1) * 255).astype(np.uint8), cv2.COLOR_RGB2BGR)


def dump(layers, out_dir, cam_x, cam_y, zoom):
    os.makedirs(out_dir, exist_ok=True)
    views = {
        '01_composite': to_bgr(render(layers, cam_x, cam_y, zoom)[0]),
        '02_depth': (layers['depth'] * 255).astype(np.uint8),
        '03_mask': (layers['alpha'] * 255).astype(np.uint8),
        '04_bg_ai': to_bgr(layers['bg_color']),
        '05_hole': (layers['hole'] * 255).astype(np.uint8),
        '06_overlay': to_bgr(layers['image'] * 0.5 + np.array([0.5, 0, 0]) * layers['alpha'][..., None]),
        '07_composite_tilt': to_bgr(render(layers, 0.10, 0.0, zoom)[0]),
    }
    for name, img in views.items():
        cv2.imwrite(os.path.join(out_dir, name + '.png'), img)
    print(f'已导出 {len(views)} 张到 {out_dir}')


def main():
    global PACK_LONG_SIDE
    ap = argparse.ArgumentParser()
    ap.add_argument('--image', required=True)
    ap.add_argument('--pack', type=int, default=PACK_LONG_SIDE)
    ap.add_argument('--no-ai', action='store_true')
    ap.add_argument('--dump', default=None)
    ap.add_argument('--zoom', type=float, default=1.25)
    ap.add_argument('--cam', type=float, nargs=2, default=(0.0, 0.0))
    args = ap.parse_args()

    PACK_LONG_SIDE = args.pack

    img = cv2.imread(args.image, cv2.IMREAD_COLOR)
    if img is None:
        sys.exit(f'读不到图片：{args.image}')

    t0 = time.time()
    dsess, dname = load_depth()
    depth = estimate_depth(dsess, dname, img)
    depth = refine_depth(depth, img)
    t1 = time.time()
    migan = None if args.no_ai else load_migan()
    layers = decompose(img, depth, migan, use_ai=not args.no_ai)
    t2 = time.time()
    print(f'深度 {t1 - t0:.2f}s · 分层 {t2 - t1:.2f}s · AI 改动 {layers["ai_delta"]:.1f}')

    if args.dump:
        dump(layers, args.dump, args.cam[0], args.cam[1], args.zoom)
        return

    cam_x, cam_y = args.cam
    tx, ty = cam_x, cam_y
    mode = 0
    titles = ['合成', '深度', '主体遮罩', '背景板/AI', '洞', '遮罩叠加']
    cv2.namedWindow('spatial-sim', cv2.WINDOW_NORMAL)
    cv2.resizeWindow('spatial-sim', 540, 960)
    while True:
        comp, (sx, sy) = render(layers, cam_x, cam_y, args.zoom)
        img_show = to_bgr(comp)
        if mode == 1:
            img_show = cv2.applyColorMap((layers['depth'] * 255).astype(np.uint8), cv2.COLORMAP_INFERNO)
        elif mode == 2:
            img_show = cv2.cvtColor((layers['alpha'] * 255).astype(np.uint8), cv2.COLOR_GRAY2BGR)
        elif mode == 3:
            img_show = to_bgr(layers['bg_color'])
        elif mode == 4:
            img_show = cv2.cvtColor((layers['hole'].astype(np.uint8) * 255), cv2.COLOR_GRAY2BGR)
        elif mode == 5:
            img_show = to_bgr(layers['image'] * 0.5 + np.array([0.5, 0, 0]) * layers['alpha'][..., None])
        cv2.putText(img_show, f'{titles[mode]}  shift=({sx:.0f},{sy:.0f})px', (12, 28),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 0), 2)
        cv2.imshow('spatial-sim', img_show)
        k = cv2.waitKey(16) & 0xFF
        if k == 27:
            break
        elif k in (ord('w'), ord('W')):
            ty += 0.03
        elif k in (ord('s'), ord('S')):
            ty -= 0.03
        elif k in (ord('a'), ord('A')):
            tx -= 0.03
        elif k in (ord('d'), ord('D')):
            tx += 0.03
        elif k in (ord('q'), ord('Q')):
            args.zoom = max(1.0, args.zoom - 0.02)
        elif k in (ord('e'), ord('E')):
            args.zoom = min(2.0, args.zoom + 0.02)
        elif k in (ord('r'), ord('R')):
            tx = ty = 0.0
        elif ord('1') <= k <= ord('6'):
            mode = k - ord('1')
        elif k in (ord('p'), ord('P')):
            name = f'sim_{int(time.time())}.png'
            cv2.imwrite(name, img_show)
            print('saved', name)
        cam_x += (tx - cam_x) * 0.2
        cam_y += (ty - cam_y) * 0.2
    cv2.destroyAllWindows()


if __name__ == '__main__':
    main()
